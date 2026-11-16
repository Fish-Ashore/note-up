package com.noteup.service;

import com.vladsch.flexmark.ext.anchorlink.AnchorLinkExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 渲染链路的最后一环：把笔记原文转换为可直接展示的 HTML。
 *
 * <p>整体分为三步：</p>
 * <ol>
 *   <li>{@link ObsidianPreprocessor} 先处理 Obsidian 方言，并把公式抽成占位符；</li>
 *   <li>flexmark 解析预处理后的 Markdown 并渲染为 HTML；</li>
 *   <li>对 HTML 做后处理：把引用块中的 callout 语法改写成结构化 DOM、把图片地址
 *       重写到资源接口、还原公式占位符。</li>
 * </ol>
 *
 * <p>为什么选择 HTML 后处理而不是自定义 flexmark 扩展：callout 与图片重写都依赖解析后的
 * 块结构和已转义的属性值，在渲染结果上做一次扫描比扩展 AST 更直接；代价是匹配规则依赖
 * flexmark 的固定输出形态（例如软换行会变成 {@code <br />}）。{@link Parser} 与
 * {@link HtmlRenderer} 在构造时按同一份配置一次性创建并可复用，二者均支持并发渲染；
 * 本类不保存每次请求的状态，因此可以作为单例组件注入。</p>
 */
@Component
public class MarkdownRenderer {

    /** 匹配整个引用块；{@code [\s\S]*?} 允许跨行并采用非贪婪，但嵌套引用会在第一个结束标签处截断。 */
    private static final Pattern BLOCKQUOTE = Pattern.compile("<blockquote>([\\s\\S]*?)</blockquote>");
    /** 匹配开始标签 {@code <img ...>}，大小写不敏感；不要求自闭合斜杠。 */
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    /** 只匹配双引号形式的 {@code src="..."}；单引号或无引号属性不会被重写。 */
    private static final Pattern SRC_ATTR = Pattern.compile("src=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    /**
     * callout 类型到默认中文标题的映射。
     *
     * <p>Obsidian 自带类型使用英文关键字，页面面向中文用户，因此标题为空时用这里的译名兜底；
     * 未收录的类型直接显示原关键字，保证自定义 callout 仍然可用。映射不可变，避免运行期被修改。</p>
     */
    private static final Map<String, String> CALLOUT_LABELS = Map.ofEntries(
            Map.entry("note", "提示"),
            Map.entry("tip", "技巧"),
            Map.entry("hint", "提示"),
            Map.entry("important", "重要"),
            Map.entry("warning", "警告"),
            Map.entry("caution", "注意"),
            Map.entry("attention", "注意"),
            Map.entry("danger", "危险"),
            Map.entry("error", "错误"),
            Map.entry("bug", "缺陷"),
            Map.entry("info", "信息"),
            Map.entry("todo", "待办"),
            Map.entry("question", "问题"),
            Map.entry("help", "帮助"),
            Map.entry("faq", "常见问题"),
            Map.entry("quote", "引用"),
            Map.entry("cite", "引用"),
            Map.entry("example", "示例"),
            Map.entry("success", "成功"),
            Map.entry("check", "完成"),
            Map.entry("done", "完成"),
            Map.entry("failure", "失败"),
            Map.entry("fail", "失败"),
            Map.entry("missing", "缺失"),
            Map.entry("abstract", "摘要"),
            Map.entry("summary", "摘要"),
            Map.entry("tldr", "摘要")
    );

    /** 方言预处理器；自身无状态，可作为单例复用。 */
    private final ObsidianPreprocessor preprocessor;
    /** 按同一份配置构建、构建后只读的解析器与渲染器，支持并发使用。 */
    private final Parser parser;
    private final HtmlRenderer renderer;

    /**
     * 构建解析器与渲染器。
     *
     * <p>扩展选择覆盖 Obsidian/GFM 的常用语法：表格、删除线、任务列表、裸链接自动识别
     * 以及标题锚点。{@code SOFT_BREAK} 设为 {@code <br />\n}，是为了贴合 Obsidian 中
     * “单个换行即换行”的编辑体验（CommonMark 默认把软换行当作空格）。开启
     * {@code GENERATE_HEADER_ID} 让标题带 id，供 wiki 链接的 {@code #标题} 锚点跳转；
     * 锚点不包裹标题文字（{@code ANCHORLINKS_WRAP_TEXT=false}），避免整段标题变成链接
     * 而影响文本选择与样式，同时用 {@code heading-anchor} 类交由前端控制显示。</p>
     *
     * <p>解析器与渲染器共享同一份 options，确保扩展和渲染选项完全一致。</p>
     *
     * @param preprocessor Obsidian 方言预处理器
     */
    public MarkdownRenderer(ObsidianPreprocessor preprocessor) {
        this.preprocessor = preprocessor;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create(),
                AnchorLinkExtension.create()
        ));
        options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");
        options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
        options.set(AnchorLinkExtension.ANCHORLINKS_WRAP_TEXT, false);
        options.set(AnchorLinkExtension.ANCHORLINKS_ANCHOR_CLASS, "heading-anchor");
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    /**
     * 渲染一篇笔记。
     *
     * <p>顺序不可随意调整：callout 必须在 flexmark 之后处理，因为需要识别引用块结构；
     * 图片重写放在 callout 之后，这样 callout 内部的图片同样会被重写；公式还原放在最后，
     * 避免公式文本被当作 HTML 属性或参与其他匹配，同时占位符即使位于 callout 内也能还原。</p>
     *
     * @param markdown 笔记原文
     * @param notePath 笔记在仓库中的相对路径，用于解析相对图片地址
     * @param context  链接与附件解析上下文
     * @return 渲染后的 HTML，以及本次识别出的链接和标签
     */
    public Rendered render(String markdown, String notePath, WikiContext context) {
        ObsidianPreprocessor.Result processed = preprocessor.process(markdown, context);
        String html = renderer.render(parser.parse(processed.markdown()));
        html = renderCallouts(html);
        html = rewriteImages(html, notePath, context);
        html = restoreMath(html, processed.math());
        return new Rendered(html, processed.links(), processed.tags());
    }

    /**
     * 扫描渲染结果中的引用块，把符合 Obsidian callout 语法的块替换为结构化 HTML。
     *
     * <p>采用“记录上次复制位置、只在命中时追加”的单次遍历：未命中的引用块不更新
     * {@code last}，最终由末尾的 {@code substring} 原样补齐，因此普通引用不受影响；
     * 完全没有命中时直接返回原串，省去一次整体拷贝。</p>
     *
     * @param html flexmark 渲染出的 HTML
     * @return 完成 callout 改写后的 HTML
     */
    private String renderCallouts(String html) {
        Matcher matcher = BLOCKQUOTE.matcher(html);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String inner = matcher.group(1);
            String replacement = convertCallout(inner);
            if (replacement == null) {
                continue;
            }
            result.append(html, last, matcher.start());
            result.append(replacement);
            last = matcher.end();
        }
        if (last == 0) {
            return html;
        }
        result.append(html.substring(last));
        return result.toString();
    }

    /**
     * 尝试把一个引用块的内部 HTML 转换为 callout；不是 callout 时返回 {@code null}。
     *
     * <p>Obsidian 语法形如 {@code > [!warning]- 标题}，渲染后大致变成
     * {@code <blockquote><p>[!warning]- 标题<br />正文...}。这里定位第一个 {@code <p>}，
     * 要求其内容紧跟 {@code [!}，从而把普通引用排除在外；类型只接受字母、数字、
     * 下划线与连字符，空类型直接判为非法。类型后的 {@code ]} 是可选的，用于兼容省略
     * 方括号的写法；再往后的 {@code -} 或 {@code +} 表示默认折叠或展开。</p>
     *
     * <p>标题截止于第一个 {@code <br}、换行或 {@code </p>}——软换行被配置为
     * {@code <br />}，所以必须把 {@code <br} 当作标题与正文的分界。标题为空时使用中文
     * 映射表兜底。正文会去掉开头的换行标签及第一个 {@code </p>}，避免段落的结束标签
     * 提前闭合外层容器，最后再去除首尾空白。</p>
     *
     * <p>类型名还会经过白名单清洗后才拼入 class，防止通过构造类型名注入额外属性。
     * 标题与正文都来自 flexmark 的输出、已完成文本转义，这里按 HTML 片段原样拼接，
     * 不做二次转义；其安全性因此取决于上游是否放行原始 HTML。</p>
     *
     * @param inner 引用块内部的 HTML
     * @return 替换用 HTML；不是 callout 时返回 {@code null}
     */
    private String convertCallout(String inner) {
        int p = inner.indexOf("<p>");
        if (p < 0) {
            return null;
        }
        // callout 标记必须紧跟在引用块第一段的开头。
        int start = p + 3;
        if (!inner.startsWith("[!", start)) {
            return null;
        }
        int typeEnd = start + 2;
        while (typeEnd < inner.length()
                && (Character.isLetterOrDigit(inner.charAt(typeEnd)) || inner.charAt(typeEnd) == '-'
                || inner.charAt(typeEnd) == '_')) {
            typeEnd++;
        }
        if (typeEnd == start + 2) {
            return null;
        }
        String type = inner.substring(start + 2, typeEnd).toLowerCase(Locale.ROOT);
        if (typeEnd < inner.length() && inner.charAt(typeEnd) == ']') {
            typeEnd++;
        }
        String fold = "";
        // - 表示默认折叠，+ 表示默认展开；没有这两个符号时渲染为普通卡片。
        if (typeEnd < inner.length() && (inner.charAt(typeEnd) == '-' || inner.charAt(typeEnd) == '+')) {
            fold = String.valueOf(inner.charAt(typeEnd));
            typeEnd++;
        }
        int titleStart = typeEnd;
        while (titleStart < inner.length() && Character.isWhitespace(inner.charAt(titleStart))) {
            titleStart++;
        }
        // 标题在软换行、显式换行或段落结束处终止。
        int titleEnd = inner.length();
        int br = inner.indexOf("<br", titleStart);
        int nl = inner.indexOf('\n', titleStart);
        int closeP = inner.indexOf("</p>", titleStart);
        if (br >= 0) {
            titleEnd = Math.min(titleEnd, br);
        }
        if (nl >= 0) {
            titleEnd = Math.min(titleEnd, nl);
        }
        if (closeP >= 0) {
            titleEnd = Math.min(titleEnd, closeP);
        }
        String title = inner.substring(titleStart, titleEnd).strip();
        if (title.isEmpty()) {
            title = CALLOUT_LABELS.getOrDefault(type, type);
        }
        String body = inner.substring(titleEnd);
        // 去掉标题与正文之间的第一个 <br>（软换行产物）。
        if (body.startsWith("<br")) {
            int gt = body.indexOf('>');
            body = gt >= 0 ? body.substring(gt + 1) : "";
        }
        body = body.replaceFirst("^\\s*", "");
        // 移除首段自己的 </p>，因为正文被放入 <div>，提前闭合会产生非法嵌套。
        int firstClose = body.indexOf("</p>");
        if (firstClose >= 0) {
            body = body.substring(0, firstClose) + body.substring(firstClose + 4);
        }
        body = body.strip();
        // class 只允许小写字母、数字、下划线和连字符，杜绝属性注入。
        String safeType = type.replaceAll("[^a-z0-9_-]", "");

        if ("-".equals(fold) || "+".equals(fold)) {
            String open = "+".equals(fold) ? " open" : "";
            return "<details class=\"callout callout-" + safeType + "\"" + open + ">"
                    + "<summary>" + title + "</summary>"
                    + "<div class=\"callout-body\">" + body + "</div>"
                    + "</details>";
        }
        return "<div class=\"callout callout-" + safeType + "\">"
                + "<div class=\"callout-title\">" + title + "</div>"
                + "<div class=\"callout-body\">" + body + "</div>"
                + "</div>";
    }

    /**
     * 把 HTML 中 {@code <img>} 的 {@code src} 重写为站内资源地址。
     *
     * <p>预处理阶段生成的 wiki 嵌入图片已经是 {@code /api/assets/...}，会被识别为站内
     * 地址原样保留；这里主要处理 Markdown 原生图片语法 {@code ![alt](path)} 以及笔记里
     * 手写的 {@code <img>}。flexmark 会把 URL 中的 {@code &} 转义成 {@code &amp;}，
     * 因此先反转义再解析，写回时重新做属性转义；且只替换 {@code src} 的值本身，
     * 其他属性及其顺序保持不变。没有 {@code src} 或无法解析的标签直接跳过。</p>
     *
     * <p>与 callout 一样使用 {@code last} 游标拼接，未改写的部分逐字保留。</p>
     *
     * @param html     渲染出的 HTML
     * @param notePath 当前笔记路径，用于解析相对地址
     * @param context  附件解析上下文
     * @return 重写后的 HTML
     */
    private String rewriteImages(String html, String notePath, WikiContext context) {
        Matcher matcher = IMG_TAG.matcher(html);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String tag = matcher.group();
            Matcher srcMatcher = SRC_ATTR.matcher(tag);
            if (!srcMatcher.find()) {
                continue;
            }
            String src = unescapeHtml(srcMatcher.group(1));
            String resolved = resolveAsset(src, notePath, context);
            if (resolved == null) {
                continue;
            }
            String newTag = tag.substring(0, srcMatcher.start(1)) + escapeAttribute(resolved)
                    + tag.substring(srcMatcher.end(1));
            result.append(html, last, matcher.start());
            result.append(newTag);
            last = matcher.end();
        }
        if (last == 0) {
            return html;
        }
        result.append(html.substring(last));
        return result.toString();
    }

    /**
     * 把图片 {@code src} 解析为资源接口 URL。
     *
     * <p>先放行外部地址与已经生成的站内地址：{@code http/https}、{@code data:}、
     * {@code /api/} 以及协议相对的 {@code //}。协议相对地址仍会指向第三方主机，
     * 这里保持原样而不是拦截，是当前内容可信模型的体现。其余按 Obsidian 习惯解析：
     * 去掉 {@code ./} 后先用完整相对路径查附件表；查不到时再只用文件名查一次，
     * 因为 Obsidian 常写 {@code ![[image.png]]} 而不带目录；仍未命中时，绝对路径去掉
     * 前导斜杠，相对路径则以当前笔记目录为基准做点段归一化。最后由 {@link #assetUrl}
     * 统一编码。</p>
     *
     * @param src      图片原始地址（已做 HTML 反转义）
     * @param notePath 当前笔记的相对路径
     * @param context  附件解析上下文
     * @return 可访问的资源 URL；{@code src} 为空时返回 {@code null}
     */
    private String resolveAsset(String src, String notePath, WikiContext context) {
        if (src == null || src.isBlank()) {
            return null;
        }
        String lower = src.toLowerCase(Locale.ROOT);
        // 外部地址和已生成的资源地址不再改写。
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")
                || lower.startsWith("/api/") || lower.startsWith("//")) {
            return src;
        }
        String cleaned = src;
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        String mapped = context.attachment(cleaned);
        if (mapped == null) {
            // Obsidian 常用短路径：完整路径查不到时按文件名再查一次。
            String filename = cleaned.substring(cleaned.lastIndexOf('/') + 1);
            mapped = context.attachment(filename);
        }
        String path;
        if (mapped != null) {
            path = mapped;
        } else if (cleaned.startsWith("/")) {
            path = cleaned.substring(1);
        } else {
            path = normalizeRelative(parentDir(notePath), cleaned);
        }
        return assetUrl(path);
    }

    /**
     * 取笔记路径的父目录。
     *
     * @param notePath 笔记相对路径
     * @return 父目录；笔记位于仓库根目录时返回空字符串
     */
    private static String parentDir(String notePath) {
        int slash = notePath.lastIndexOf('/');
        return slash < 0 ? "" : notePath.substring(0, slash);
    }

    /**
     * 归一化由基准目录和相对路径拼成的仓库内路径。
     *
     * <p>用双端队列模拟路径栈：忽略空段与 {@code .}；遇到 {@code ..} 时弹出上一段；
     * 当已经在根目录时，多余的 {@code ..} 会被忽略而不是报错，从而保证结果不会逃出
     * 仓库根目录，避免路径穿越。这里只做文本归一化，不访问文件系统，
     * 也不做 URL 解码。</p>
     *
     * @param base     基准目录，可为空字符串
     * @param relative 相对路径
     * @return 以 {@code /} 分隔的归一化路径
     */
    private static String normalizeRelative(String base, String relative) {
        String combined = base.isBlank() ? relative : base + "/" + relative;
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : combined.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack);
    }

    /**
     * 把仓库内相对路径编码为资源接口 URL。
     *
     * <p>统一反斜杠、去掉一个前导斜杠后按 {@code /} 分段，逐段做 UTF-8 编码，保留
     * 目录分隔符；{@link URLEncoder} 的表单编码会把空格写成 {@code +}，这里替换为
     * {@code %20}，确保路径段在浏览器中按字面意义解析。</p>
     *
     * @param path 仓库内相对路径
     * @return 形如 {@code /api/assets/a/b.png} 的 URL
     */
    private static String assetUrl(String path) {
        StringBuilder encoded = new StringBuilder("/api/assets/");
        String[] segments = path.replace('\\', '/').replaceFirst("^/", "").split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    /**
     * 把公式占位符还原为原始公式文本。
     *
     * <p>占位符按编号唯一，{@link String#replace} 会替换其全部出现位置。还原时对公式做
     * HTML 转义：公式里的 {@code <}、{@code &} 等必须作为文本显示，不能重新变成标签或
     * 实体。定界符 {@code $}/{@code $$} 一并保留，前端可按需再做数学排版。</p>
     *
     * @param html 后处理完成的 HTML
     * @param math 按编号保存的公式原文
     * @return 公式已还原的 HTML
     */
    private String restoreMath(String html, List<String> math) {
        String result = html;
        for (int i = 0; i < math.size(); i++) {
            result = result.replace("NOTEUMATHPLACEHOLDER" + i + "X", escapeHtml(math.get(i)));
        }
        return result;
    }

    /**
     * 还原 HTML 属性中被 flexmark 转义的字符。
     *
     * <p>用于读取 {@code src}：解析前必须恢复 {@code &} 等原始字符；写完再转义回去。
     * {@code &amp;} 放在最后处理，避免把 {@code &amp;lt;} 二次还原成 {@code <}。</p>
     *
     * @param value 已转义的属性文本
     * @return 还原后的文本
     */
    private static String unescapeHtml(String value) {
        return value.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /**
     * 转义放入双引号属性值中的文本。
     *
     * <p>先替换 {@code &}，避免后续生成的实体被再次转义；这里不处理单引号，因为本类
     * 生成的所有属性都用双引号包围。调用方保证入参非 {@code null}。</p>
     *
     * @param value 原始属性值
     * @return 可安全放入双引号属性的文本
     */
    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * 在属性转义的基础上额外转义单引号，用于可能出现在 HTML 文本节点中的内容。
     *
     * @param value 原始文本
     * @return 可安全放入 HTML 文本或单/双引号属性的文本
     */
    private static String escapeHtml(String value) {
        return escapeAttribute(value).replace("'", "&#39;");
    }

    /**
     * 单篇笔记的渲染结果。
     *
     * <p>{@code html} 直接存入笔记表供页面读取；{@code links} 与 {@code tags} 由预处理阶段
     * 收集，索引层据此重建反向链接和标签关联。两个列表在预处理返回前已做不可变拷贝，
     * 调用方无需再防御性复制。</p>
     *
     * @param html  最终 HTML
     * @param links 识别出的 wiki 链接
     * @param tags  识别出的标签
     */
    public record Rendered(String html, List<ObsidianPreprocessor.LinkRef> links, List<String> tags) {
    }
}