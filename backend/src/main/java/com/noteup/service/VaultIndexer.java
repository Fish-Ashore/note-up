package com.noteup.service;

import com.noteup.config.AppProperties;
import com.noteup.model.IndexResult;
import com.noteup.repo.LinkRepository;
import com.noteup.repo.NoteRepository;
import com.noteup.repo.TagRepository;
import com.noteup.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Vault 索引器：把 Obsidian 风格的 Markdown 仓库同步进数据库，是文件系统与站点数据之间的桥梁。
 *
 * <p><b>索引策略。</b>每次重建都做一次全量目录扫描，而不是依赖文件监听：监听在进程重启、
 * 容器重建或用户直接改库之后会丢失事件，全量扫描则始终以磁盘为唯一事实来源，结果可重建、
 * 可预期。写入侧通过 {@code path} 唯一键做幂等 upsert，因此重复执行不会产生重复笔记。
 * 正文的 SHA-256 会随笔记一起保存，为后续“内容未变则跳过渲染”的增量化留出入口，但当前流程
 * 仍会对每篇已发布笔记重新渲染（{@link IndexResult#skipped()} 恒为 0）。</p>
 *
 * <p><b>一次重建的顺序。</b>扫描文件 → 解析 frontmatter/正文 → 分配唯一 slug → upsert 基础字段
 * → 为已发布笔记建立“标题/别名/路径 → slug/id”的解析表 → 渲染 HTML 并重建标签、双链
 * → 把本轮未出现的笔记标记为删除 → 重建全文索引与清理孤立标签。先落基础数据再渲染，是为了让
 * 双链解析能引用到同一轮中刚插入的笔记 id。</p>
 *
 * <p><b>一致性与并发。</b>{@link #reindex()} 标注 {@code @Transactional} 并加 {@code synchronized}，
 * 保证同一实例内不会有两个重建交错写入；整个扫描、解析与渲染都在同一个事务里完成，因此对
 * 大仓库而言事务会偏长，但换来了“要么整轮生效、要么整轮回滚”的原子性。</p>
 */
@Service
public class VaultIndexer {

    private static final Logger log = LoggerFactory.getLogger(
        VaultIndexer.class
    );
    /** 与笔记无关的目录，扫描时整棵跳过，避免把 Obsidian 配置、版本库元数据和依赖目录当成内容。 */
    private static final Set<String> IGNORED_DIRS = Set.of(
        ".obsidian",
        ".trash",
        ".git",
        ".github",
        "node_modules"
    );

    private final AppProperties properties;
    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final LinkRepository linkRepository;
    private final MarkdownRenderer renderer;
    /** 使用 SafeConstructor，禁止 YAML 反序列化出任意 Java 类型，避免恶意 frontmatter 触发代码执行。 */
    private final Yaml yaml = new Yaml(
        new SafeConstructor(new LoaderOptions())
    );

    /** 最近一次重建的统计结果，供管理后台展示；{@code volatile} 保证读取线程能立即看到新值。 */
    private volatile IndexResult lastResult;

    /**
     * 构造索引器。
     *
     * @param properties     提供 Vault 根目录等配置
     * @param noteRepository 笔记持久化
     * @param tagRepository  标签持久化
     * @param linkRepository 双链持久化
     * @param renderer       Markdown 渲染器，负责生成 HTML、内联标签与链接引用
     */
    public VaultIndexer(
        AppProperties properties,
        NoteRepository noteRepository,
        TagRepository tagRepository,
        LinkRepository linkRepository,
        MarkdownRenderer renderer
    ) {
        this.properties = properties;
        this.noteRepository = noteRepository;
        this.tagRepository = tagRepository;
        this.linkRepository = linkRepository;
        this.renderer = renderer;
    }

    /**
     * 返回最近一次重建的统计结果。
     *
     * @return 上次重建结果；进程启动后尚未重建时为 {@code null}
     */
    public IndexResult lastResult() {
        return lastResult;
    }

    /**
     * 全量重建索引：扫描 Vault、同步笔记、渲染内容并重建标签与双链。
     *
     * <p>方法整体运行在一个事务中，任何未捕获的异常都会让本轮写入回滚；单篇笔记的解析或渲染
     * 失败则被降级为“失败计数”，不会中断其余笔记的处理，以免一个坏文件拖垮整站索引。</p>
     *
     * @return 本轮扫描、发布、删除、失败数量及耗时
     * @throws ApiException Vault 根目录不存在或无法遍历时抛出，对应 500
     */
    @Transactional
    public synchronized IndexResult reindex() {
        long started = System.currentTimeMillis();
        String now = Instant.now().toString();
        Path root = Path.of(properties.getVaultRoot())
            .toAbsolutePath()
            .normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Vault 目录不存在: " + root
            );
        }

        // 一次遍历同时得到 Markdown 与附件两份清单；排序保证每次处理的顺序稳定，便于复现问题。
        List<Path> markdownFiles;
        List<Path> attachmentFiles;
        try {
            markdownFiles = walk(root, true);
            attachmentFiles = walk(root, false);
        } catch (IOException e) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "扫描 Vault 失败: " + e.getMessage()
            );
        }

        // 附件映射同时登记“相对路径”和“文件名”，让 ![[image.png]] 与 ![[assets/image.png]] 都能命中。
        Map<String, String> attachments = buildAttachmentMap(
            root,
            attachmentFiles
        );
        List<FileMeta> metas = new ArrayList<>();
        // 记录本轮实际见到的路径，稍后用于识别磁盘上已被删除的笔记。
        Set<String> seenPaths = new LinkedHashSet<>();
        int failed = 0;

        for (Path file : markdownFiles) {
            String relative = relative(root, file);
            seenPaths.add(relative);
            try {
                metas.add(parse(file, relative));
            } catch (Exception e) {
                // 单个文件读取失败时仍保留一条占位记录，使已有笔记不会被误判为“已删除”。
                failed++;
                log.warn("Failed to parse note {}: {}", relative, e.toString());
                metas.add(FileMeta.failed(relative, file));
            }
        }

        // slug 必须在写库前统一分配：冲突判断既看本轮已占用的 slug，也看数据库中其他路径的旧 slug。
        assignSlugs(metas);
        for (FileMeta meta : metas) {
            // 先写基础字段拿到稳定的笔记 id，后续渲染与双链才能引用它；deleted_at 由 upsert 顺带清空。
            meta.id = noteRepository.upsert(
                meta.path,
                meta.slug,
                meta.title,
                meta.category,
                meta.body,
                meta.hash,
                meta.publish,
                meta.createdAt,
                meta.updatedAt
            );
        }

        // 只有已发布笔记参与双链解析，草稿不应暴露给读者，也不应被其他笔记链接到。
        Map<String, String> slugByKey = new HashMap<>();
        Map<String, Long> idByKey = new HashMap<>();
        int published = 0;
        int unpublished = 0;
        for (FileMeta meta : metas) {
            if (!meta.publish) {
                unpublished++;
                continue;
            }
            published++;
            // 用标题、文件名、slug、相对路径和别名共同建立索引，贴近 Obsidian 中 [[链接]] 的多种写法。
            register(slugByKey, idByKey, meta.title, meta.slug, meta.id);
            register(
                slugByKey,
                idByKey,
                baseName(meta.path),
                meta.slug,
                meta.id
            );
            register(slugByKey, idByKey, meta.slug, meta.slug, meta.id);
            register(slugByKey, idByKey, meta.path, meta.slug, meta.id);
            for (String alias : meta.aliases) {
                register(slugByKey, idByKey, alias, meta.slug, meta.id);
            }
        }

        WikiContext context = new WikiContext(slugByKey, idByKey, attachments);
        for (FileMeta meta : metas) {
            if (!meta.publish) {
                // 取消发布或转为草稿时清空渲染产物与派生数据，避免旧内容继续通过接口泄露。
                noteRepository.clearRendered(meta.id);
                tagRepository.clearForNote(meta.id);
                linkRepository.clearForSource(meta.id);
                continue;
            }
            try {
                MarkdownRenderer.Rendered rendered = renderer.render(
                    meta.body,
                    meta.path,
                    context
                );
                noteRepository.updateRendered(
                    meta.id,
                    rendered.html(),
                    meta.hash,
                    now
                );
                // 标签和链接采用“先清后插”：文件里删掉的标签/链接必须同步消失，不能只做增量追加。
                tagRepository.clearForNote(meta.id);
                for (String tag : mergedTags(meta, rendered.tags())) {
                    tagRepository.attach(meta.id, tag);
                }
                linkRepository.clearForSource(meta.id);
                for (ObsidianPreprocessor.LinkRef link : rendered.links()) {
                    // targetNoteId 可能为 null，表示链接指向尚不存在的笔记（Obsidian 允许先写链接）。
                    linkRepository.insert(
                        meta.id,
                        link.targetTitle(),
                        link.targetNoteId()
                    );
                }
            } catch (Exception e) {
                // 渲染失败时退回“不可见”状态，宁可暂时不展示，也不展示半成品或被注入的 HTML。
                failed++;
                log.warn(
                    "Failed to render note {}: {}",
                    meta.path,
                    e.toString()
                );
                noteRepository.clearRendered(meta.id);
                tagRepository.clearForNote(meta.id);
                linkRepository.clearForSource(meta.id);
            }
        }

        // 本轮未见到的路径统一软删除。注意：Vault 为空、挂载失败或配置指向错误目录时，这里会把
        // 全部笔记置为已删除，因此重建前应确认根目录确实可读。
        int deleted = noteRepository.markMissing(seenPaths, now);
        // FTS 采用整表重建而非逐条维护，逻辑简单、不会残留已删除内容；代价是每轮重写整张索引表。
        noteRepository.rebuildFts();
        tagRepository.cleanupOrphans();

        // skipped 目前恒为 0：全量重建尚未根据 content_hash 跳过未变化文件。
        IndexResult result = new IndexResult(
            metas.size(),
            published,
            unpublished,
            0,
            deleted,
            failed,
            System.currentTimeMillis() - started,
            now
        );
        lastResult = result;
        log.info(
            "Reindex finished: scanned={}, published={}, unpublished={}, deleted={}, failed={}, {}ms",
            result.scanned(),
            result.published(),
            result.unpublished(),
            result.deleted(),
            result.failed(),
            result.durationMs()
        );
        return result;
    }

    /**
     * 递归遍历 Vault，按扩展名筛选出 Markdown 或附件文件。
     *
     * <p>隐藏目录（以 {@code .} 开头）和 {@link #IGNORED_DIRS} 中的文件一律排除；结果按相对路径
     * 排序，保证索引顺序稳定。这里先收集再排序，而不是边遍历边处理。</p>
     *
     * @param root     Vault 根目录
     * @param markdown {@code true} 返回 {@code .md} 文件，{@code false} 返回其余附件
     * @return 排好序的绝对路径列表
     * @throws IOException 目录不可读时抛出
     */
    private List<Path> walk(Path root, boolean markdown) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String relative = root.relativize(path).toString();
                    for (String segment : relative.split("[/\\\\]")) {
                        if (segment.isBlank()) {
                            continue;
                        }
                        if (
                            IGNORED_DIRS.contains(segment) ||
                            segment.startsWith(".")
                        ) {
                            return false;
                        }
                    }
                    String name = path
                        .getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT);
                    return markdown
                        ? name.endsWith(".md")
                        : !name.endsWith(".md");
                })
                .sorted(Comparator.comparing(path -> relative(root, path)))
                .toList();
        }
    }

    /**
     * 建立附件查找表：键为归一化后的相对路径或文件名，值为可直接用于 URL 的相对路径。
     *
     * <p>同一文件名可能出现在不同目录，因此用 {@code putIfAbsent} 保留按路径排序后的第一个，
     * 让解析结果稳定；完整路径先于裸文件名注册，减少歧义。</p>
     *
     * @param root  Vault 根目录
     * @param files 附件文件列表
     * @return 归一化键到相对路径的映射
     */
    private Map<String, String> buildAttachmentMap(
        Path root,
        List<Path> files
    ) {
        Map<String, String> map = new HashMap<>();
        for (Path file : files) {
            String relative = relative(root, file);
            String key = WikiContext.normalize(relative);
            map.putIfAbsent(key, relative);
            String filename = file.getFileName().toString();
            map.putIfAbsent(WikiContext.normalize(filename), relative);
        }
        return map;
    }

    /**
     * 为所有笔记分配全局唯一 slug。
     *
     * <p>优先级为 frontmatter 的 {@code slug} > 文件名，经 {@link #sanitizeSlug} 规整。冲突时先追加
     * 路径短哈希（同一文件每次重建得到相同后缀，URL 不会随机漂移），仍冲突才追加自增序号，
     * 以应对极端哈希碰撞。</p>
     *
     * @param metas 本轮解析出的笔记元数据，方法会直接写入其 {@code slug} 字段
     */
    private void assignSlugs(List<FileMeta> metas) {
        Map<String, String> used = new HashMap<>();
        for (FileMeta meta : metas) {
            String base = sanitizeSlug(
                meta.slugSource == null || meta.slugSource.isBlank()
                    ? baseName(meta.path)
                    : meta.slugSource
            );
            String candidate = base;
            if (conflicts(candidate, meta.path, used)) {
                candidate = base + "-" + shortHash(meta.path);
            }
            int counter = 2;
            while (conflicts(candidate, meta.path, used)) {
                candidate = base + "-" + shortHash(meta.path) + "-" + counter++;
            }
            used.put(candidate, meta.path);
            meta.slug = candidate;
        }
    }

    /**
     * 判断 slug 是否已被“其他文件”占用。
     *
     * <p>同时检查本轮已分配的映射与数据库中的旧记录；允许同一路径复用原 slug，这样常规重建不会
     * 把自己判成冲突。逐条查库在最坏情况下是 N 次查询，但重建是低频管理操作，换取实现简单与
     * 跨轮次一致性。</p>
     *
     * @param slug 待检查的 slug
     * @param path 当前笔记相对路径
     * @param used 本轮已分配 slug 到路径的映射
     * @return 存在其他路径占用时返回 {@code true}
     */
    private boolean conflicts(
        String slug,
        String path,
        Map<String, String> used
    ) {
        String usedBy = used.get(slug);
        if (usedBy != null && !usedBy.equals(path)) {
            return true;
        }
        String existingPath = noteRepository.findPathBySlug(slug);
        return existingPath != null && !existingPath.equals(path);
    }

    /**
     * 解析单个 Markdown 文件，得到可直接落库的元数据。
     *
     * <p>读取固定使用 UTF-8，并先把 CRLF/CR 归一化为 LF：同一份内容在不同操作系统上应产生相同的
     * 哈希与渲染结果，否则仅仅换行符差异就会造成无意义的变更。只有文件以 {@code ---} 开头时才尝试
     * 解析 frontmatter，YAML 非法时仅告警并按“无元数据”处理（正文仍会去掉头部），避免一处坏
     * 元数据让整轮索引失败。</p>
     *
     * <p>几个刻意的默认值：{@code publish} 缺省为 {@code false}，即新文件默认不公开，防止误发；
     * 标题缺省取文件名；时间字段依次尝试多个常见键名，最后回退到文件系统时间，兼容不同来源的
     * Vault。内容哈希只覆盖正文，不包含 frontmatter。</p>
     *
     * @param file     文件绝对路径，用于读取属性
     * @param relative 相对 Vault 根目录的路径，作为笔记的稳定标识
     * @return 解析结果
     * @throws IOException 文件读取失败时抛出，由调用方计为失败并放置占位记录
     */
    private FileMeta parse(Path file, String relative) throws IOException {
        // 文件系统时间只是 frontmatter 缺失时的兜底，创建时间在某些平台上并不可靠。
        BasicFileAttributes attrs = Files.readAttributes(
            file,
            BasicFileAttributes.class
        );
        String fallbackCreated = attrs.creationTime().toInstant().toString();
        String fallbackUpdated = attrs
            .lastModifiedTime()
            .toInstant()
            .toString();
        String raw = Files.readString(file, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n');

        Map<String, Object> frontmatter = new HashMap<>();
        String body = raw;
        if (raw.startsWith("---\n")) {
            int end = findFrontmatterEnd(raw);
            if (end > 0) {
                String yamlText = raw.substring(4, end);
                int bodyStart = raw.indexOf('\n', end + 1);
                body = bodyStart >= 0 ? raw.substring(bodyStart + 1) : "";
                try {
                    Object loaded = yaml.load(yamlText);
                    if (loaded instanceof Map<?, ?> map) {
                        // 键统一转成字符串，避免 YAML 把 true/数字之类写成非字符串键导致后续取不到。
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            frontmatter.put(
                                String.valueOf(entry.getKey()),
                                entry.getValue()
                            );
                        }
                    }
                } catch (Exception e) {
                    log.warn(
                        "Invalid frontmatter in {}: {}",
                        relative,
                        e.getMessage()
                    );
                }
            }
        }

        FileMeta meta = new FileMeta();
        meta.path = relative;
        meta.body = body;
        meta.hash = sha256(body);
        meta.publish = asBoolean(frontmatter.get("publish"));
        // 标题为空或只有空白时用文件名兜底，保证列表页永远有可展示的名称。
        String title = asString(frontmatter.get("title"));
        meta.title =
            title == null || title.isBlank() ? baseName(relative) : title;
        meta.category = asString(frontmatter.get("category"));
        meta.slugSource = asString(frontmatter.get("slug"));
        // 时间字段允许多种命名，按优先级取第一个非空值，减少对用户书写习惯的约束。
        meta.createdAt = firstNonNull(
            asString(frontmatter.get("created")),
            asString(frontmatter.get("date")),
            asString(frontmatter.get("created_at")),
            fallbackCreated
        );
        meta.updatedAt = firstNonNull(
            asString(frontmatter.get("updated")),
            asString(frontmatter.get("modified")),
            asString(frontmatter.get("updated_at")),
            fallbackUpdated
        );
        meta.aliases = new ArrayList<>();
        meta.tags = new LinkedHashSet<>();
        for (String alias : asList(
            frontmatter.get("aliases"),
            frontmatter.get("alias")
        )) {
            if (!alias.isBlank()) {
                meta.aliases.add(alias);
            }
        }
        for (String tag : asList(
            frontmatter.get("tags"),
            frontmatter.get("tag")
        )) {
            // Obsidian 允许标签写成 #tag，入库前去掉 #，与正文内联标签保持同一种表示。
            String cleaned = tag.startsWith("#") ? tag.substring(1) : tag;
            if (!cleaned.isBlank()) {
                meta.tags.add(cleaned);
            }
        }
        return meta;
    }

    /**
     * 定位 frontmatter 的结束行。
     *
     * <p>结束标记必须独占一行，且只能是 {@code ---} 或 {@code ...}；逐行顺序扫描而不是用正则，
     * 是为了正确处理正文中的分隔线与代码块内容。找不到结束标记时返回 {@code -1}，调用方会把整个
     * 文件当作正文，避免把未闭合的头部误当成元数据吞掉内容。</p>
     *
     * @param raw 已归一化为 LF 的完整文件内容
     * @return 结束标记所在行的起始下标；不存在时返回 {@code -1}
     */
    private int findFrontmatterEnd(String raw) {
        int from = 4;
        while (from < raw.length()) {
            int lineStart = raw.indexOf('\n', from);
            if (lineStart < 0) {
                return -1;
            }
            int next = lineStart + 1;
            if (raw.startsWith("---", next) || raw.startsWith("...", next)) {
                int lineEnd = raw.indexOf('\n', next);
                String line =
                    lineEnd < 0
                        ? raw.substring(next)
                        : raw.substring(next, lineEnd);
                if (line.strip().equals("---") || line.strip().equals("...")) {
                    return next;
                }
            }
            from = next;
        }
        return -1;
    }

    /**
     * 合并 frontmatter 标签与正文内联标签，去重并保持出现顺序。
     *
     * @param meta   笔记元数据
     * @param inline 渲染器从正文中提取的标签
     * @return 合并后的标签集合
     */
    private Set<String> mergedTags(FileMeta meta, List<String> inline) {
        Set<String> tags = new LinkedHashSet<>(meta.tags);
        for (String tag : inline) {
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /**
     * 把一个可能的链接目标登记进解析表。
     *
     * <p>使用 {@code putIfAbsent} 让先登记者获胜：同一篇笔记会以标题、文件名、slug、路径、别名
     * 多次登记，而不同笔记之间若出现同名标题，则保留排序更靠前的那个，避免解析结果随遍历顺序抖动。
     * 键统一经 {@link WikiContext#normalize} 归一化，使大小写、斜杠与 {@code .md} 后缀差异都能匹配。</p>
     *
     * @param slugs 归一化键到 slug 的映射
     * @param ids   归一化键到笔记 id 的映射
     * @param key   原始链接目标
     * @param slug  目标笔记 slug
     * @param id    目标笔记 id
     */
    private static void register(
        Map<String, String> slugs,
        Map<String, Long> ids,
        String key,
        String slug,
        long id
    ) {
        if (key == null || key.isBlank()) {
            return;
        }
        String normalized = WikiContext.normalize(key);
        slugs.putIfAbsent(normalized, slug);
        ids.putIfAbsent(normalized, id);
    }

    /**
     * 计算文件相对 Vault 根目录的路径，并统一使用 {@code /} 分隔。
     *
     * <p>数据库中的 path 会作为跨平台稳定标识参与 slug 冲突判断和删除检测，因此不能在 Windows
     * 写成反斜杠，否则同一文件在不同环境会被视作两条记录。</p>
     *
     * @param root 根目录
     * @param file 目标文件
     * @return 以 {@code /} 分隔的相对路径
     */
    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 取路径最后一段并去掉 {@code .md} 后缀，用作默认标题或默认 slug 来源。
     *
     * @param path 相对路径
     * @return 不含目录与扩展名的文件名
     */
    private static String baseName(String path) {
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        return name;
    }

    /**
     * 把任意文本规整成 URL 友好的 slug。
     *
     * <p>保留 Unicode 字母和数字（因此中文标题能直接生成可读 slug），把空白、斜杠和其余符号压成
     * 单个连字符，并去掉首尾连字符。结果为空时回退为 {@code note}，保证 slug 永远非空。</p>
     *
     * @param value 原始文本，通常来自 frontmatter 或文件名
     * @return 规整后的 slug
     */
    static String sanitizeSlug(String value) {
        String slug = value
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s/\\\\]+", "-")
            .replaceAll("[^\\p{L}\\p{N}_-]+", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "note" : slug;
    }

    /**
     * 取 SHA-256 十六进制串的前 6 位，作为 slug 冲突后缀。
     *
     * @param value 通常为笔记相对路径
     * @return 6 个十六进制字符
     */
    private static String shortHash(String value) {
        return sha256(value).substring(0, 6);
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制摘要。
     *
     * <p>固定字符集很关键：哈希要在不同机器、不同默认编码下保持一致，才能作为内容版本标识。</p>
     *
     * @param value 待计算文本
     * @return 64 个十六进制字符
     * @throws IllegalStateException 运行环境缺少 SHA-256 实现时抛出（正常 JVM 不会发生）
     */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 宽松地把 frontmatter 值解释为布尔值。
     *
     * <p>YAML 里 {@code publish} 可能写成布尔、字符串甚至数字，这里接受 {@code true/yes/1}，
     * 其余（包括缺失）一律视为不发布，遵循“默认私有”的安全取向。</p>
     *
     * @param value 原始值
     * @return 是否为真
     */
    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return text.equals("true") || text.equals("yes") || text.equals("1");
    }

    /**
     * 把 frontmatter 值转成去空白的字符串；日期统一转为 ISO-8601。
     *
     * <p>SnakeYAML 会把未加引号的日期解析成 {@link Date}，这里统一序列化，避免数据库里出现
     * 平台相关的日期格式；空字符串按 {@code null} 处理，便于调用方做缺省回退。</p>
     *
     * @param value 原始值
     * @return 字符串值；原始值为空或转换后为空时返回 {@code null}
     */
    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 把 frontmatter 中的列表字段（如 aliases/tags）统一展开成字符串列表。
     *
     * <p>兼容三种常见写法：YAML 列表、逗号分隔字符串、单值。传入多个候选键是为了同时接受单数与
     * 复数形式，调用方无需关心用户用了哪个键名。</p>
     *
     * @param values 候选原始值
     * @return 清理后的非空字符串列表
     */
    private static List<String> asList(Object... values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    String text = asString(item);
                    if (text != null) {
                        result.add(text);
                    }
                }
            } else {
                String text = asString(value);
                if (text != null) {
                    for (String part : text.split(",")) {
                        if (!part.isBlank()) {
                            result.add(part.trim());
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 返回第一个非空白字符串，全部缺失时退回当前时间。
     *
     * <p>时间字段最终必须非空，否则数据库排序与展示会出现空值；“当前时间”只是最后兜底，
     * 正常情况下应命中 frontmatter 或文件系统时间。</p>
     *
     * @param values 候选值
     * @return 首个非空值，或当前时间的 ISO-8601 表示
     */
    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return Instant.now().toString();
    }

    /**
     * 单个文件的解析中间态。
     *
     * <p>把“磁盘上的文件”与“数据库中的行”连接起来：{@code path} 用于 upsert 定位，{@code slug}
     * 由统一分配阶段写入，{@code id} 在 upsert 后回填供后续渲染与链接使用。字段设为私有并直接访问，
     * 是因为它只在索引器内部流转，不构成对外契约。</p>
     */
    private static final class FileMeta {

        private long id;
        private String path;
        private String slug;
        private String slugSource;
        private String title;
        private String category;
        private String body;
        private String hash;
        private boolean publish;
        private String createdAt;
        private String updatedAt;
        private List<String> aliases = new ArrayList<>();
        private Set<String> tags = new LinkedHashSet<>();

        /**
         * 为读取或解析失败的文件构造占位记录。
         *
         * <p>关键目的是让该路径出现在 {@code seenPaths} 中，从而不会被 {@code markMissing} 判为删除；
         * 同时把 {@code publish} 设为 {@code false}，宁可暂时下线，也不把空正文当作正常内容发布。
         * 代价是本次会把库中该笔记的正文覆盖为空并取消发布，直到下次成功解析才恢复（原文件仍在磁盘）。
         * 时间取文件最后修改时间，取不到时退回当前时间。</p>
         *
         * @param path 相对路径
         * @param file 原始文件，用于读取修改时间
         * @return 标记为不发布的占位元数据
         */
        private static FileMeta failed(String path, Path file) {
            FileMeta meta = new FileMeta();
            meta.path = path;
            meta.title = baseName(path);
            meta.body = "";
            meta.hash = "";
            meta.publish = false;
            String modified = Instant.now().toString();
            try {
                modified = Files.getLastModifiedTime(file)
                    .toInstant()
                    .toString();
            } catch (IOException ignored) {
                // keep fallback
            }
            meta.createdAt = modified;
            meta.updatedAt = modified;
            return meta;
        }
    }
}
