package com.noteup.model;

/**
 * 搜索结果条目，供全文搜索接口返回。
 *
 * <p>优先由 SQLite FTS5 的 {@code bm25()} 生成，查询失败时降级为 LIKE 查询。
 * 注意 {@code rank} 为 bm25 分值，<b>越小越相关</b>，结果已按升序排好；降级查询没有
 * 真实排序依据，统一填 {@code 0.0}。</p>
 *
 * @param id       笔记主键
 * @param slug     笔记 URL 标识
 * @param title    标题
 * @param category 分类，可为 {@code null}
 * @param snippet  摘要片段，命中的关键词以 {@code <mark>} 包裹，已清理 Obsidian 语法
 * @param rank     相关性分值，越小越相关
 */
public record SearchHit(
        long id,
        String slug,
        String title,
        String category,
        String snippet,
        double rank
) {
}