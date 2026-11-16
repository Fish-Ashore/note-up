package com.noteup.service;

import java.util.Map;

/**
 * 一次 Markdown 渲染所需的解析上下文快照。
 *
 * <p>在 Markdown 渲染链路中，{@link ObsidianPreprocessor} 需要把 Obsidian 方言里的
 * wiki 链接目标（标题、别名、文件名或相对路径）解析为已发布笔记的 slug、数据库 id
 * 或附件的真实路径；这些映射由 {@code VaultIndexer} 在每轮索引开始时统一构建，
 * 然后以本记录的形式传给渲染器。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>三个映射都以 {@link #normalize(String)} 处理后的字符串为键，从而实现大小写不敏感、
 *       忽略前导斜杠与 {@code .md} 后缀的统一查找；</li>
 *   <li>上下文本身不可变，映射在构建完成后不再修改，可安全地在多个笔记的渲染过程中复用，
 *       避免为每个链接重复查询数据库；</li>
 *   <li>查不到时返回 {@code null}，由调用方决定降级策略（例如渲染为“缺失链接”）。</li>
 * </ul>
 *
 * @param slugByKey        规范化名称到笔记 URL slug 的映射
 * @param idByKey          规范化名称到笔记数据库 id 的映射，用于建立反向链接
 * @param attachmentByKey  规范化名称到附件相对路径的映射，用于图片与嵌入资源
 */
public record WikiContext(
        Map<String, String> slugByKey,
        Map<String, Long> idByKey,
        Map<String, String> attachmentByKey
) {

    /**
     * 查找 wiki 链接目标对应的笔记 slug。
     *
     * @param target wiki 链接中的目标文本
     * @return 笔记 slug；目标不存在或未发布时返回 {@code null}
     */
    public String noteSlug(String target) {
        return slugByKey.get(normalize(target));
    }

    /**
     * 查找 wiki 链接目标对应的笔记 id。
     *
     * @param target wiki 链接中的目标文本
     * @return 笔记 id；目标不存在或未发布时返回 {@code null}
     */
    public Long noteId(String target) {
        return idByKey.get(normalize(target));
    }

    /**
     * 查找附件（图片等资源）的相对路径。
     *
     * @param target 附件名称或路径，通常来自 {@code ![[...]]} 嵌入
     * @return 相对路径；附件不存在时返回 {@code null}
     */
    public String attachment(String target) {
        return attachmentByKey.get(normalize(target));
    }

    /**
     * 将名称规范化为统一的查找键。
     *
     * <p>处理步骤依次为：去除首尾空白、把 Windows 反斜杠统一为正斜杠、去掉前导斜杠、
     * 去掉可选的 {@code .md} 后缀，最后转为小写。这样 {@code " /Notes/Foo.MD "} 与
     * {@code "notes/foo"} 会命中同一个条目，符合 Obsidian 对链接大小写不敏感的习惯。</p>
     *
     * @param value 原始名称，可为 {@code null}
     * @return 规范化后的键；入参为 {@code null} 时返回空字符串
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".md")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.toLowerCase();
    }
}