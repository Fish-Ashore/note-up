---
title: Markdown 渲染管线
slug: markdown-pipeline
publish: true
category: 前端
tags: [markdown, flexmark, katex, mermaid, 渲染]
created: 2026-09-22
updated: 2026-10-27
---

# Markdown 渲染管线

Markdown 只在后端渲染一次，前端直接展示 HTML。这样双链、标题锚点和反向链接都只有一套解析结果。

## 流程图

```mermaid
flowchart TD
    A[原始 Markdown] --> B[剥离 Dataview 与注释]
    B --> C[保护公式]
    C --> D[转换 WikiLink / 图片 / 标签]
    D --> E[flexmark 渲染]
    E --> F[处理 Callout]
    F --> G[重写图片路径]
    G --> H[还原公式占位符]
    H --> I[前端 v-html]
```

## 公式保护

块级公式必须在进入 Markdown 解析器之前替换成占位符，否则会被拆成多个段落，反斜杠也会被转义：

```java
if (stripped.startsWith("$$")) {
    // 捕获到闭合的 $$ 后整体替换为 NOTEUMATHPLACEHOLDER
}
```

## 前端增强

```js
const mermaid = (await import('mermaid')).default
const hljs = (await import('highlight.js/lib/common')).default
const renderMath = (await import('katex/contrib/auto-render')).default
```

> [!warning] 不要前后端各解析一次
> 如果前端再解析一遍 Markdown，双链与标题锚点会和后端结果不一致，反向链接也会错位。

相关笔记：[[Obsidian方言指南]]、[[索引器设计]]、[[数学公式示例]]。

#渲染 #前端