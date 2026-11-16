package com.noteup.service;

import com.noteup.model.Note;
import com.noteup.model.NoteDetail;
import com.noteup.model.NoteSummary;
import com.noteup.model.SearchHit;
import com.noteup.model.TagCount;
import com.noteup.repo.LinkRepository;
import com.noteup.repo.NoteRepository;
import com.noteup.repo.TagRepository;
import com.noteup.web.ApiException;
import com.noteup.web.PageResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 公开站点的笔记读取服务。
 *
 * <p>本类只做“查询 + 视图组装”，不负责写入：笔记内容由 {@link VaultIndexer} 在重建时写入，
 * 这里直接消费已落库的 {@code content_raw}/{@code content_html}，因此请求路径上不再做 Markdown
 * 渲染，读性能稳定且与文件系统解耦。</p>
 *
 * <p>可见性规则集中在这里：对外接口只返回 {@code publish = true} 且未被软删除的笔记，草稿和
 * 已删除内容只在管理端可见。分页统一采用 0 基页码，并把 size 限制在 1–100，防止一次请求拉取
 * 过多数据拖垮数据库与响应体。</p>
 */
@Service
public class NoteService {

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final LinkRepository linkRepository;

    /**
     * 构造笔记读取服务。
     *
     * @param noteRepository 笔记查询
     * @param tagRepository  标签查询
     * @param linkRepository 反向链接查询
     */
    public NoteService(NoteRepository noteRepository, TagRepository tagRepository, LinkRepository linkRepository) {
        this.noteRepository = noteRepository;
        this.tagRepository = tagRepository;
        this.linkRepository = linkRepository;
    }

    /**
     * 分页查询已发布笔记，可按分类、标签和关键词过滤。
     *
     * <p>先查当前页数据再查总数，两次查询使用相同的过滤条件，使分页信息与数据尽量对应；方法未包
     * 事务，极端并发写入下总数可能与列表略有偏差。页码与大小在这里做兜底校正，避免上层传入
     * 负数或过大的值。</p>
     *
     * @param category 分类，为空表示不过滤
     * @param tag      标签，为空表示不过滤
     * @param q        关键词，为空表示不过滤
     * @param page     页码，从 0 开始，负数按 0 处理
     * @param size     每页条数，限制在 1–100
     * @return 当前页数据及总数
     */
    public PageResponse<NoteSummary> list(String category, String tag, String q, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Note> notes = noteRepository.listPublished(category, tag, q, safeSize, safePage * safeSize);
        long total = noteRepository.countPublished(category, tag, q);
        List<NoteSummary> items = notes.stream().map(this::toSummary).toList();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    /**
     * 按 slug 读取笔记详情，并附带标签与反向链接。
     *
     * <p>可见性判断放在内存里而不是追加到 SQL，是为了让“已发布且未删除”这条规则在服务层显式可读，
     * 也让底层查询保持简单；列表接口的同类过滤则在仓储 SQL 中完成。反向链接只暴露 slug 与标题，
     * 足够前端跳转即可。</p>
     *
     * @param slug 笔记 slug
     * @return 详情视图
     * @throws ApiException 笔记不存在、为草稿或已删除时抛出 404，不区分具体原因以免泄露草稿信息
     */
    public NoteDetail detail(String slug) {
        Note note = noteRepository.findBySlug(slug)
                .filter(n -> n.publish() && n.deletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("笔记不存在"));
        List<NoteDetail.Backlink> backlinks = noteRepository.listBacklinks(note.id()).stream()
                .map(n -> new NoteDetail.Backlink(n.slug(), n.title()))
                .toList();
        return new NoteDetail(note.id(), note.slug(), note.title(), note.category(),
                tagRepository.listForNote(note.id()), note.contentHtml(), note.createdAt(),
                note.updatedAt(), backlinks);
    }

    /**
     * 列出所有标签及其引用次数。
     *
     * @return 标签计数列表，供标签云或筛选器使用
     */
    public List<TagCount> tags() {
        return tagRepository.listCounts();
    }

    /**
     * 列出全部分类。
     *
     * @return 分类名称列表
     */
    public List<String> categories() {
        return noteRepository.categories();
    }

    /**
     * 全文搜索已发布笔记。
     *
     * <p>优先走 SQLite FTS5：它支持前缀匹配和相关性排序，结果质量明显优于 {@code LIKE}。当 FTS 不可用
     * 或查询表达式被拒绝时抛出 {@link DataAccessException}，此时降级为 {@code LIKE} 模糊匹配，保证
     * 搜索功能不会因索引异常而整体不可用；代价是失去排名与分词能力。空查询直接返回空列表，
     * 避免生成无意义的全表扫描。</p>
     *
     * @param q     查询词，按空白拆分为多个必须同时命中的词
     * @param limit 返回上限，限制在 1–50
     * @return 命中的搜索结果
     */
    public List<SearchHit> search(String q, int limit) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        String fts = toFtsQuery(q);
        List<SearchHit> hits;
        try {
            hits = noteRepository.search(fts, safeLimit);
        } catch (DataAccessException e) {
            hits = noteRepository.searchLike("%" + q.trim() + "%", safeLimit);
        }
        // FTS snippet 仍带 Markdown 标记，返回前清理，避免前端出现 [[双链]]、代码块等噪声。
        return hits.stream()
                .map(hit -> new SearchHit(hit.id(), hit.slug(), hit.title(), hit.category(),
                        cleanSnippet(hit.snippet()), hit.rank()))
                .toList();
    }

    /**
     * 清理搜索摘要中的 Markdown 语法，保留可读正文。
     *
     * <p>摘要由数据库片段函数生成，无法保证在语法边界上截断，因此这里按常见模式逐类剥离：
     * 嵌入、双链、高亮、块引用标记、代码围栏，最后压缩空白。正则只求“足够好”，不追求完整解析
     * Markdown，以免在搜索热路径上引入过重开销。</p>
     *
     * @param snippet 原始摘要
     * @return 清理后的纯文本；输入为空时返回空串
     */
    private static String cleanSnippet(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        return snippet
                .replaceAll("!\\[\\[[^\\]]*]]", " ")
                .replaceAll("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?]]", "$1")
                .replaceAll("\\[![^\\]]+\\][+-]?", " ")
                .replaceAll("(?m)^\\s*\\^[A-Za-z0-9-]+\\s*$", " ")
                .replaceAll("```[A-Za-z0-9_-]*", " ")
                .replaceAll("```", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 管理端笔记清单：包含草稿与已删除记录，用于排查索引结果。
     *
     * @return 全部笔记（按仓储默认排序）
     */
    public List<Note> adminNotes() {
        return noteRepository.listAll();
    }

    /**
     * 查询某篇笔记的出链目标标题。
     *
     * @param noteId 源笔记 id
     * @return 链接指向的标题列表，可能包含尚未创建的笔记
     */
    public List<String> linksOf(long noteId) {
        return linkRepository.targetTitles(noteId);
    }

    /**
     * 把用户输入转换成 FTS5 查询表达式。
     *
     * <p>每个词都被包成短语并追加前缀通配，既允许“按前缀边输边搜”，又通过引号避免用户输入
     * {@code AND/OR/NEAR} 等 FTS 运算符改变查询语义；词之间用 {@code AND} 连接，保证多词同时命中。
     * 内部双引号做转义，防止构造出非法表达式。</p>
     *
     * @param q 原始查询
     * @return FTS5 MATCH 表达式
     */
    private static String toFtsQuery(String q) {
        List<String> terms = new ArrayList<>();
        for (String term : q.trim().split("\\s+")) {
            String cleaned = term.replace("\"", "\"\"");
            if (!cleaned.isBlank()) {
                terms.add("\"" + cleaned + "\"*");
            }
        }
        return String.join(" AND ", terms);
    }

    /**
     * 把数据库行组装成列表视图。
     *
     * <p>摘要从 {@code content_raw} 现算而不落库，是为了避免再维护一份派生字段；正文规模有限，
     * 且列表 SQL 已分页，开销可控。标签则按笔记逐条查询，换取模型简单，单页最多 100 条。</p>
     *
     * @param note 笔记实体
     * @return 列表项视图
     */
    private NoteSummary toSummary(Note note) {
        return new NoteSummary(note.id(), note.slug(), note.title(), note.category(),
                tagRepository.listForNote(note.id()), note.updatedAt(), excerpt(note.contentRaw()));
    }

    /**
     * 从原始 Markdown 生成纯文本摘要。
     *
     * <p>顺序上先整体删除代码块和注释，再做行内替换，避免代码中的 {@code [[...]]} 之类被误当成
     * 链接；图片与链接只保留可读文字，最后去除控制排版的行首符号。长度截断在字符层面完成，
     * 中文与英文都按同一上限处理。</p>
     *
     * @param raw 原始 Markdown
     * @return 不超过约 180 字的纯文本摘要；输入为空时返回空串
     */
    static String excerpt(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?s)%%.*?%%", " ")
                .replaceAll("!\\[\\[[^\\]]*]]", " ")
                .replaceAll("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?]]", "$1")
                .replaceAll("\\[![^\\]]+\\][+-]?", " ")
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s*\\^[A-Za-z0-9-]+\\s*$", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[#>*_`~=^|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() <= 180) {
            return text;
        }
        return text.substring(0, 180) + "…";
    }
}