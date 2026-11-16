package com.noteup.service;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Obsidian 方言的预处理阶段，位于 Markdown 渲染链路的最前端。
 *
 * <p>flexmark 只理解 CommonMark/GFM，直接解析 Obsidian 专有语法会得到错误结果：
 * {@code [[链接]]} 会被当成普通方括号文本，{@code %%注释%%} 会原样显示，公式里的下划线
 * 还可能被当作强调标记。因此本类在 flexmark 解析之前逐行扫描原始 Markdown，完成：</p>
 * <ol>
 *   <li>识别并保护围栏代码块、行内代码与行内/块级公式，使后续替换不触碰其内容；</li>
 *   <li>丢弃 {@code dataview} 代码块——它是运行时查询，静态站点无法执行；</li>
 *   <li>移除 Obsidian 的块引用标记 {@code ^block-id}；</li>
 *   <li>删除 {@code %%...%%} 注释（支持跨行）；</li>
 *   <li>把 wiki 链接与嵌入转换成 HTML，把 {@code ==高亮==} 转成 {@code <mark>}，
 *       把 {@code #标签} 转成可点击链接；</li>
 *   <li>把公式替换为占位符，交给渲染后的
 *       {@code MarkdownRenderer#restoreMath} 原样还原。</li>
 * </ol>
 *
 * <p>扫描使用显式状态机（是否在围栏代码块内、是否在块级公式内、注释是否开启），
 * 因为这些结构都可以跨行，逐行独立地用正则替换会破坏它们的边界。处理的同时收集
 * wiki 链接和标签，供索引层写入反向链接与标签表。</p>
 *
 * <p>本类没有实例状态（注释状态在每次 {@link #process} 调用内新建），可被多线程共享。</p>
 */
@Component
public class ObsidianPreprocessor {

    /** 行首围栏：三个及以上反引号或波浪号，加可选的信息串；分组 1 是围栏标记，分组 2 是语言/信息。 */
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})\\s*(.*)$");
    /**
     * wiki 链接或嵌入：可选的 {@code !} 表示嵌入，内部文本排除 {@code ]} 与换行并采用非贪婪匹配。
     * 只支持单层方括号，标签里出现 {@code ]} 时不会被识别。
     */
    private static final Pattern WIKI_LINK = Pattern.compile("!?\\[\\[([^\\]\\n]+?)]]");
    /**
     * 行内标签：{@code #} 前不能是单词字符、{@code #} 或 {@code /}，避免命中 {@code C#}、
     * 标题标记 {@code ###}、URL 片段；标签体允许 Unicode 字母数字及 {@code _ / -}，
     * 以支持 Obsidian 的嵌套标签（如 {@code #project/2026}）。
     */
    private static final Pattern INLINE_TAG = Pattern.compile("(?<![\\w#/])#([\\p{L}\\p{N}_/-]+)");
    /** 高亮语法 {@code ==文本==}，限定单行且内部不含等号，避免跨段误匹配。 */
    private static final Pattern HIGHLIGHT = Pattern.compile("==([^=\\n]+?)==");
    /**
     * 块引用标记：行首或空白后的 {@code ^id}，id 由字母、数字、连字符组成并一直延续到行尾。
     * 它只服务于 Obsidian 内部跳转，渲染前需要剥离。
     */
    private static final Pattern BLOCK_ID = Pattern.compile("(?:^|\\s+)\\^[A-Za-z0-9-]+\\s*$");
    /**
     * 单行块级公式 {@code $$...$$}。先于行内公式处理，否则 {@code $$} 会被行内规则
     * 拆成两个单美元符号，留下孤立的 {@code $}。
     */
    private static final Pattern DISPLAY_MATH = Pattern.compile("\\$\\$([^$\\n]+)\\$\\$");
    /**
     * 行内公式 {@code $...$}：前面不能是反斜杠（{@code \$} 是转义的美元符号），
     * 内部不含美元符号与换行，非贪婪以支持一行内多个公式。
     */
    private static final Pattern INLINE_MATH = Pattern.compile("(?<!\\\\)\\$([^$\\n]+?)\\$");
    /** 按扩展名判定 wiki 嵌入是否为图片，扩展名统一小写后比较。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "bmp", "ico");

    /**
     * 对一篇笔记的 Markdown 做全部 Obsidian 方言转换。
     *
     * <p>处理顺序与状态机的优先级是刻意安排的：块级公式的续行优先消费整行（其中的
     * 反引号不应开启代码块）；围栏内的内容原样保留（只有 dataview 围栏整体丢弃）；
     * 围栏与公式之外才识别 {@code %%} 注释和块引用标记；最后按“行内代码分段”处理
     * 普通文本。公式被替换为占位符而不是直接输出，是为了让 flexmark 不会改写公式里的
     * Markdown 元字符。</p>
     *
     * @param markdown 原始笔记内容
     * @param context  链接与附件的解析上下文
     * @return 预处理后的 Markdown、被抽出的公式文本、去重后的链接和标签
     */
    public Result process(String markdown, WikiContext context) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() + 256);
        List<String> math = new ArrayList<>();
        LinkedHashMap<String, LinkRef> links = new LinkedHashMap<>();
        LinkedHashSet<String> tags = new LinkedHashSet<>();

        boolean inFence = false;
        boolean skipFence = false;
        boolean inDisplayMath = false;
        String fenceMarker = null;
        StringBuilder displayMath = new StringBuilder();
        CommentState comment = new CommentState();

        for (String line : lines) {
            String stripped = line.stripLeading();

            // 块级公式优先：续行原样收集，直到遇到 $$；期间不解析反引号、注释等任何语法。
            if (inDisplayMath) {
                displayMath.append(line).append('\n');
                int close = line.indexOf("$$");
                if (close >= 0) {
                    inDisplayMath = false;
                    math.add(displayMath.toString().strip());
                    displayMath.setLength(0);
                    out.append(mathPlaceholder(math.size() - 1)).append('\n');
                }
                continue;
            }

            // 围栏代码块：以“与开启标记同字符、长度不少于开启长度”的行首前缀判定闭合；dataview 块整体不输出。
            if (inFence) {
                boolean closing = stripped.startsWith(fenceMarker);
                if (closing) {
                    inFence = false;
                    if (!skipFence) {
                        out.append(line).append('\n');
                    }
                    skipFence = false;
                    fenceMarker = null;
                } else if (!skipFence) {
                    out.append(line).append('\n');
                }
                continue;
            }

            // 只有去掉缩进后以三个及以上反引号/波浪号开头的行才开启代码块，避免把行内反引号误判为围栏。
            Matcher fence = FENCE.matcher(stripped);
            if (fence.matches()) {
                String marker = fence.group(1);
                String info = fence.group(2).trim().toLowerCase(Locale.ROOT);
                inFence = true;
                // 记录围栏字符（反引号或波浪号）与长度，闭合时据此判定。
                fenceMarker = marker.substring(0, 1).repeat(marker.length());
                if (info.startsWith("dataview")) {
                    skipFence = true;
                } else {
                    out.append(line).append('\n');
                }
                continue;
            }

            // 注释内的 $$ 不开启公式，避免注释里的示例公式被抽出；支持同一行内闭合。
            if (!comment.inside && stripped.startsWith("$$")) {
                int closing = stripped.indexOf("$$", 2);
                if (closing >= 0) {
                    math.add(stripped.strip());
                    out.append(mathPlaceholder(math.size() - 1)).append('\n');
                } else {
                    inDisplayMath = true;
                    displayMath.append(line).append('\n');
                }
                continue;
            }

            // 块引用 id 只是 Obsidian 编辑器锚点，先剥离，再做行内语法转换。
            String withoutBlockId = BLOCK_ID.matcher(line).replaceFirst("");
            processLine(withoutBlockId, out, math, links, tags, context, comment);
            out.append('\n');
        }

        // 文件结束时仍未闭合，把已收集的内容当作一个公式收尾，避免内容丢失。
        if (inDisplayMath) {
            math.add(displayMath.toString().strip());
            out.append(mathPlaceholder(math.size() - 1)).append('\n');
        }

        return new Result(out.toString(), math, List.copyOf(links.values()), List.copyOf(tags));
    }

    /**
     * 生成公式占位符。
     *
     * <p>占位符只由大写字母和数字组成，不含 Markdown 元字符，flexmark 解析时既不会被改写，
     * 也不会像 {@code $$...$$} 那样触发强调或转义；渲染完成后再按编号还原。
     * 编号保证同一篇笔记中的多个公式互不混淆。</p>
     *
     * @param index 公式在本次处理中的序号
     * @return 形如 {@code NOTEUMATHPLACEHOLDER0X} 的占位文本
     */
    private static String mathPlaceholder(int index) {
        return "NOTEUMATHPLACEHOLDER" + index + "X";
    }

    /**
     * 处理一行普通文本：按反引号切成“正文 / 行内代码”交替片段。
     *
     * <p>反引号按出现顺序配对，偶数下标的片段位于代码之外，交给
     * {@link #transformText} 做方言转换；奇数下标的片段位于一对反引号之间，原样输出
     * （并补回被 {@code split} 当作分隔符去掉的反引号）。因此行内代码里的 wiki 链接、
     * 标签、公式与 {@code %%} 注释都不会被误处理。</p>
     *
     * @param line    已剥离块引用 id 的一行文本
     * @param out     输出缓冲区
     * @param math    公式收集列表
     * @param links   链接收集表
     * @param tags    标签收集集合
     * @param context 链接与附件解析上下文
     * @param comment 跨行的注释状态
     */
    private void processLine(String line, StringBuilder out, List<String> math,
                             Map<String, LinkRef> links, Set<String> tags,
                             WikiContext context, CommentState comment) {
        String[] parts = line.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                out.append('`').append(parts[i]).append('`');
            } else {
                transformText(parts[i], out, math, links, tags, context, comment);
            }
        }
    }

    /**
     * 转换一段行内正文：剥离注释，再按固定顺序保护公式、替换 wiki 链接、高亮与标签。
     *
     * <p>顺序是有依赖的：先剥离 {@code %%...%%}，注释内容才不会被后续规则识别；
     * 公式必须最先被占位符替换，否则其中的 {@code ==}、{@code #}、{@code [[} 等字符会被
     * 误判；wiki 链接要先于标签处理，因为 {@code [[笔记#标题]]} 里的 {@code #} 属于锚点，
     * 若先跑标签规则会被错误拆成标签；标签产生的是最终 HTML，放在最后可避免其属性文本
     * 再被其他规则扫描。</p>
     *
     * <p>注释状态由 {@link CommentState} 跨行保存：本行未闭合时，下一行会在进入时直接
     * 处于“注释中”状态，从而实现多行注释；同一行内则可先开后闭。</p>
     *
     * @param text    不含行内代码的一段正文
     * @param out     输出缓冲区
     * @param math    公式收集列表
     * @param links   链接收集表
     * @param tags    标签收集集合
     * @param context 链接与附件解析上下文
     * @param comment 跨行注释状态
     */
    private void transformText(String text, StringBuilder out, List<String> math,
                               Map<String, LinkRef> links, Set<String> tags,
                               WikiContext context, CommentState comment) {
        StringBuilder visible = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int marker = text.indexOf("%%", index);
            if (!comment.inside) {
                // 注释外：把标记之前的可见文本原样保留，然后进入注释状态。
                if (marker < 0) {
                    visible.append(text, index, text.length());
                    break;
                }
                visible.append(text, index, marker);
                comment.inside = true;
                index = marker + 2;
            } else {
                // 注释内：丢弃到下一个 %% 为止；找不到则本行剩余内容全部丢弃，状态留到下一行。
                if (marker < 0) {
                    break;
                }
                comment.inside = false;
                index = marker + 2;
            }
        }

        String protectedText = protectMath(visible.toString(), math);
        protectedText = replaceWikiLinks(protectedText, links, context);
        protectedText = HIGHLIGHT.matcher(protectedText).replaceAll("<mark>$1</mark>");
        protectedText = replaceTags(protectedText, tags);
        out.append(protectedText);
    }

    /**
     * 把公式替换为占位符。
     *
     * <p>先处理块级公式再处理行内公式：否则 {@code $$x$$} 会先被行内规则匹配成
     * {@code $<内容>$}，残留的美元符号会原样出现在页面上。</p>
     *
     * @param text 一段正文
     * @param math 公式收集列表，按出现顺序追加
     * @return 公式被占位符替代后的文本
     */
    private String protectMath(String text, List<String> math) {
        String result = replaceMath(text, DISPLAY_MATH, math);
        return replaceMath(result, INLINE_MATH, math);
    }

    /**
     * 用正则抽出公式并写入收集列表，同时把匹配处替换为唯一占位符。
     *
     * <p>保存的是包含定界符的原始文本（如 {@code $x^2$}），还原阶段才能连同定界符一起
     * 输出；{@link Matcher#appendReplacement} 配合 {@link Matcher#quoteReplacement}，
     * 避免占位符中的字符被当作替换组引用。</p>
     *
     * @param text    待处理文本
     * @param pattern 块级或行内公式正则
     * @param math    公式收集列表
     * @return 替换后的文本
     */
    private String replaceMath(String text, Pattern pattern, List<String> math) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            math.add(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    "NOTEUMATHPLACEHOLDER" + (math.size() - 1) + "X"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 把 {@code [[目标#标题|别名]]} 形式的 wiki 链接替换为 HTML。
     *
     * <p>解析顺序：先看开头的 {@code !} 判断是否为嵌入；再以第一个 {@code |} 分出目标与
     * 显示别名（别名内可继续包含竖线）；然后以第一个 {@code #} 分出笔记目标与标题锚点。
     * {@code [[#标题]]} 没有显式笔记目标，代码会把标题文本当作查找目标（而非“当前笔记”），
     * 找不到时自然降级为缺失链接。生成的 HTML 通过 {@link Matcher#quoteReplacement}
     * 插入，避免其中的 {@code $} 或反斜杠被当作替换语法。</p>
     *
     * @param text    待处理文本
     * @param links   链接收集表，用于后续写入反向链接
     * @param context 链接与附件解析上下文
     * @return 替换后的文本
     */
    private String replaceWikiLinks(String text, Map<String, LinkRef> links, WikiContext context) {
        Matcher matcher = WIKI_LINK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            boolean embed = matcher.group().startsWith("!");
            String inner = matcher.group(1);
            String targetPart = inner;
            String label = null;
            int pipe = inner.indexOf('|');
            if (pipe >= 0) {
                targetPart = inner.substring(0, pipe);
                label = inner.substring(pipe + 1).trim();
            }
            String target = targetPart;
            String heading = null;
            int hash = targetPart.indexOf('#');
            if (hash >= 0) {
                target = targetPart.substring(0, hash).trim();
                heading = targetPart.substring(hash + 1).trim();
            }
            target = target.trim();
            if (target.isEmpty() && heading != null) {
                target = heading;
                heading = null;
            }

            String html = renderWikiLink(embed, target, heading, label, links, context);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(html));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 把解析后的 wiki 链接片段渲染成 HTML。
     *
     * <p>按“是否嵌入”和“扩展名是否为图片”分成三条路径：嵌入图片输出 {@code <img>}；
     * 非嵌入图片输出指向资源的链接；其余输出笔记链接。图片路径优先通过附件映射解析，
     * 查不到时把原始目标交给 {@link #assetUrl} 归一化。</p>
     *
     * <p>笔记链接优先使用上下文中的 slug 生成 {@code /notes/{slug}}，并按需追加标题锚点；
     * 目标未发布或不存在时降级到搜索页，同时附加 {@code wikilink-missing} 类，让前端能
     * 把“尚未创建的笔记”与正常链接区分开。所有动态内容都经过 HTML 转义：属性值用
     * {@link #escapeAttribute}，链接文字用 {@link #escapeHtml}，防止目标或别名注入标签。
     * 链接以“小写目标 + noteId”为键去重，因此同一目标多次出现只记录一次，且保留首次
     * 出现的顺序，便于索引层稳定写入。</p>
     *
     * @param embed   是否为 {@code ![[...]]} 嵌入
     * @param target  笔记名或附件路径
     * @param heading 可选的标题锚点，可为 {@code null}
     * @param label   可选的显示别名，可为 {@code null}
     * @param links   链接收集表
     * @param context 链接与附件解析上下文
     * @return 一段可直接嵌入 Markdown 的 HTML
     */
    private String renderWikiLink(boolean embed, String target, String heading, String label,
                                  Map<String, LinkRef> links, WikiContext context) {
        String extension = extensionOf(target);
        boolean image = IMAGE_EXTENSIONS.contains(extension);

        if (embed && image) {
            String path = context.attachment(target);
            if (path == null) {
                path = target;
            }
            String width = null;
            String alt = label;
            // 图片嵌入的别名可以是 "300" 或 "300x200"：视为尺寸而不是替代文字。
            if (label != null && label.matches("\\d+(x\\d+)?")) {
                width = label.contains("x") ? label.substring(0, label.indexOf('x')) : label;
                alt = null;
            }
            StringBuilder img = new StringBuilder("<img src=\"").append(escapeAttribute(assetUrl(path)))
                    .append("\" alt=\"").append(escapeAttribute(alt == null ? target : alt)).append('"');
            if (width != null && !width.isBlank()) {
                img.append(" width=\"").append(escapeAttribute(width)).append('"');
            }
            return img.append(">").toString();
        }

        String display = label != null && !label.isBlank() ? label : target;
        if (heading != null && !heading.isBlank() && (label == null || label.isBlank())) {
            // 没有别名时用 "笔记 > 标题" 表达跳转层次，和 Obsidian 的显示习惯一致。
            display = target + " > " + heading;
        }

        if (image) {
            // 非嵌入图片：Obsidian 点击后会打开资源本身，这里渲染成资源链接。
            String path = context.attachment(target);
            if (path == null) {
                path = target;
            }
            return "<a class=\"wikilink\" href=\"" + escapeAttribute(assetUrl(path)) + "\">"
                    + escapeHtml(display) + "</a>";
        }

        String slug = context.noteSlug(target);
        Long noteId = context.noteId(target);
        String href;
        String css;
        if (slug != null) {
            href = "/notes/" + slug;
            if (heading != null && !heading.isBlank()) {
                href += "#" + headingAnchor(heading);
            }
            css = "wikilink";
        } else {
            // 目标笔记不存在：保留为可点击的搜索结果，而不是生成死链。
            href = "/search?q=" + urlEncode(target);
            css = "wikilink wikilink-missing";
        }
        // noteId 可能为 null（缺失链接），拼接后仍能得到稳定的去重键。
        links.putIfAbsent(target.toLowerCase(Locale.ROOT) + "|" + noteId,
                new LinkRef(target, noteId));
        return "<a class=\"" + css + "\" href=\"" + escapeAttribute(href) + "\">"
                + escapeHtml(display) + "</a>";
    }

    /**
     * 把 {@code #标签} 替换为指向标签页的链接，并收集标签名。
     *
     * <p>纯数字标签按 Obsidian 规则不算标签，避免把“#123”这类编号误识别；空白标签同理。
     * 命中的标签加入 {@link LinkedHashSet}，保证同一篇笔记内去重且顺序稳定。未命中的
     * 匹配不调用 {@code appendReplacement}，其原文会在后续拼接时原样保留。</p>
     *
     * @param text 已处理链接与公式的文本
     * @param tags 标签收集集合
     * @return 替换后的文本
     */
    private String replaceTags(String text, Set<String> tags) {
        Matcher matcher = INLINE_TAG.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.isBlank() || name.matches("\\d+")) {
                continue;
            }
            tags.add(name);
            String replacement = "<a class=\"tag-link\" href=\"/tags/" + urlEncode(name) + "\">#"
                    + escapeHtml(name) + "</a>";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 取目标末尾的扩展名并转为小写。
     *
     * @param target 文件路径或名称
     * @return 不含点的扩展名；没有扩展名时返回空字符串
     */
    private static String extensionOf(String target) {
        int dot = target.lastIndexOf('.');
        return dot < 0 ? "" : target.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 把附件相对路径转换为受控的资源访问 URL。
     *
     * <p>先统一斜杠并去掉 {@code ./} 与前导斜杠，避免生成多余的前缀或重复分隔符；
     * 然后逐段编码——只编码路径段而不编码分隔符 {@code /}，否则整条路径会变成单个
     * 转义片段。{@link URLEncoder} 采用表单编码，会把空格变成 {@code +}，在路径中
     * 需替换为 {@code %20} 才能被浏览器正确解析。</p>
     *
     * @param path 附件相对路径
     * @return 形如 {@code /api/assets/a/b.png} 的 URL
     */
    private static String assetUrl(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        StringBuilder encoded = new StringBuilder("/api/assets/");
        String[] segments = normalized.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(urlEncode(segments[i]));
        }
        return encoded.toString();
    }

    /**
     * 由标题文本生成 URL 锚点。
     *
     * <p>规则与常见的 GitHub 风格标题 id 对齐：转小写、空白折叠为连字符、去掉除字母、
     * 数字、下划线和连字符以外的标点，最后做 URL 编码。这样 {@code [[笔记#标题]]}
     * 生成的片段与渲染器为标题生成的 id 尽量一致；但渲染器的 id 生成规则若调整，
     * 这里需要同步，否则跳转可能失效。</p>
     *
     * @param heading 标题原文
     * @return 可直接拼在 {@code #} 后的编码片段
     */
    private static String headingAnchor(String heading) {
        String slug = heading.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{L}\\p{N}_-]", "");
        return urlEncode(slug);
    }

    /**
     * 对单个路径段或查询参数值做 UTF-8 百分号编码。
     *
     * @param value 原始文本
     * @return 编码结果，空格固定为 {@code %20}
     */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 转义要放进 HTML 文本或属性值的内容，并容忍 {@code null}。
     *
     * <p>必须先替换 {@code &}，再替换尖括号和引号，否则会把已生成的实体（如
     * {@code &lt;}）再次转义成 {@code &amp;lt;}。单引号也一并转义，使结果可安全放入
     * 单引号或双引号包围的属性。</p>
     *
     * @param value 原始文本，可为 {@code null}
     * @return 转义后的文本；入参为 {@code null} 时返回空字符串
     */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 属性值转义入口，与文本转义使用同一套规则。
     *
     * <p>单独保留这个方法是为了在调用点表达意图：属性值必须同时处理双引号，
     * 未来若要收紧（例如额外处理反引号）也只需改这一处。</p>
     *
     * @param value 原始属性值
     * @return 可安全放入引号包围属性中的文本
     */
    private static String escapeAttribute(String value) {
        return escapeHtml(value);
    }

    /**
     * 一条 wiki 链接的索引记录。
     *
     * <p>{@code targetTitle} 保留用户在笔记中书写的原始目标，便于展示和调试；
     * {@code targetNoteId} 是解析后的笔记 id，为 {@code null} 表示目标尚未创建或未发布，
     * 索引层据此只记录悬空引用，不建立真实的反向链接。</p>
     *
     * @param targetTitle  链接目标的原始文本
     * @param targetNoteId 解析到的笔记 id，可为 {@code null}
     */
    public record LinkRef(String targetTitle, Long targetNoteId) {
    }

    /**
     * 预处理结果。
     *
     * <p>渲染阶段只需要 {@code markdown}；{@code math} 保存被抽出的原始公式，供渲染完成后
     * 按占位符顺序还原；{@code links} 与 {@code tags} 供索引层写入反向链接和标签。
     * 集合在返回前已做不可变拷贝，避免调用方修改内部状态。</p>
     *
     * @param markdown 可交给 flexmark 的 Markdown
     * @param math     按出现顺序保存的公式原文
     * @param links    去重后的 wiki 链接
     * @param tags     去重后的标签名
     */
    public record Result(String markdown, List<String> math, List<LinkRef> links, List<String> tags) {
    }

    /**
     * {@code %%...%%} 注释的跨行状态。
     *
     * <p>注释可以跨越多行，而 {@code process} 是逐行调用转换逻辑的，因此需要一个在整篇
     * 文档处理期间存活的可变标记。它只在单次 {@code process} 调用内创建和使用，
     * 不构成实例状态，因此不会引入线程安全问题。</p>
     */
    private static final class CommentState {
        private boolean inside;
    }
}