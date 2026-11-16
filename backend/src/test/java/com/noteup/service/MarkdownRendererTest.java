package com.noteup.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkdownRenderer} 的单元测试，不启动 Spring 容器。
 *
 * <p>测试使用真实的 {@link ObsidianPreprocessor} 与渲染器，重点验证两类行为：
 * 一是 Obsidian 方言（wiki 链接、嵌入图片、高亮、注释、Callout、Dataview、
 * 块 ID、数学公式）能否转换为安全的 HTML；二是数学公式在 Markdown 解析前后
 * 不被破坏。断言以生成 HTML 的关键片段为准，避免耦合完整输出。</p>
 */
class MarkdownRendererTest {

    /** 被测渲染器，依赖真实的 Obsidian 预处理器。 */
    private final MarkdownRenderer renderer = new MarkdownRenderer(new ObsidianPreprocessor());

    /**
     * 验证 Obsidian 方言转换及运行时专用语法的剔除。
     *
     * <p>边界场景包括：别名链接、目标不存在的链接、带宽度参数的图片嵌入、
     * 行内注释与高亮、Callout、应被移除的 Dataview 代码块、行内公式以及块 ID。
     * 最后断言链接引用被收集为 2 条（存在与缺失的目标各一条）。</p>
     */
    @Test
    void convertsObsidianDialectAndStripsRuntimeSyntax() {
        WikiContext context = new WikiContext(
                Map.of("target", "target-slug"),
                Map.of("target", 42L),
                Map.of("diagram.svg", "assets/diagram.svg")
        );

        String markdown = """
                # 标题

                链接 [[Target|别名]] 与 [[Missing]]。

                ![[diagram.svg|300]]

                这是 ==高亮== 和 %%隐藏注释%%。

                > [!warning] 小心
                > Callout 正文。

                ```dataview
                TABLE file.mtime
                ```

                行内公式 $x^2$。

                ^block-id
                """;

        MarkdownRenderer.Rendered rendered = renderer.render(markdown, "notes/hello.md", context);

        // 已存在的 wiki 链接应解析为站内 slug，并保留别名作为展示文本。
        assertTrue(rendered.html().contains("href=\"/notes/target-slug\""));
        assertTrue(rendered.html().contains(">别名</a>"));
        // 缺失目标应退化为搜索链接，并带有 wikilink-missing 样式。
        assertTrue(rendered.html().contains("wikilink-missing"));
        // 嵌入图片应改写为附件接口地址，数字别名作为宽度而非 alt 文本。
        assertTrue(rendered.html().contains("src=\"/api/assets/assets/diagram.svg\""));
        assertTrue(rendered.html().contains("width=\"300\""));
        // 高亮转为 <mark>，%% 注释必须完全从输出中移除。
        assertTrue(rendered.html().contains("<mark>高亮</mark>"));
        assertFalse(rendered.html().contains("隐藏注释"));
        // Callout 转为带类型 class 的容器，并保留正文。
        assertTrue(rendered.html().contains("callout callout-warning"));
        assertTrue(rendered.html().contains("Callout 正文"));
        // Dataview 是 Obsidian 运行时语法，服务端应整块丢弃。
        assertFalse(rendered.html().contains("TABLE file.mtime"));
        // 行内公式原样保留，块 ID 标记不应出现在最终 HTML 中。
        assertTrue(rendered.html().contains("$x^2$"));
        assertFalse(rendered.html().contains("block-id"));
        // 两个 wiki 链接（存在与缺失）都应进入链接引用集合。
        assertEquals(2, rendered.links().size());
    }

    /**
     * 验证多行块级公式在 Markdown 解析过程中被占位符保护。
     *
     * <p>边界点：{@code $$} 内的换行不能被渲染为 {@code <br>}，公式中的反斜杠
     * 命令与下标需原样保留；同时确认行内公式不受块级处理影响。</p>
     */
    @Test
    void protectsMultilineDisplayMathFromMarkdown() {
        WikiContext context = new WikiContext(Map.of(), Map.of(), Map.of());
        String markdown = """
                块级公式：

                $$
                \\int_{0}^{1} x\\,dx = \\frac{1}{2}
                $$

                行内公式 $a_b + c$。
                """;

        MarkdownRenderer.Rendered rendered = renderer.render(markdown, "math.md", context);

        // 公式内容（含 LaTeX 反斜杠）应完整保留。
        assertTrue(rendered.html().contains("\\int_{0}^{1} x\\,dx = \\frac{1}{2}"));
        // 公式开始标记后不得被 Markdown 插入换行标签。
        assertFalse(rendered.html().contains("$$<br"));
        // 行内公式保持原样，下划线不会被当作强调语法消费。
        assertTrue(rendered.html().contains("$a_b + c$"));
    }
}