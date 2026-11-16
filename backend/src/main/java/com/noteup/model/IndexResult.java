package com.noteup.model;

/**
 * 一次全量索引的执行结果，用于管理端展示与索引钩子返回值。
 *
 * <p>由 {@code VaultIndexer#reindex()} 汇总产生，也作为最近一次索引的摘要缓存。
 * 各计数字段之间并非严格的加和关系：{@code unpublished} 只统计已扫到但未发布的文件，
 * 失败的文件仍计入已发布数后再单独累加 {@code failed}。</p>
 *
 * @param scanned     本次扫描到的 Markdown 文件数
 * @param published   标记为发布并尝试渲染的笔记数
 * @param unpublished 标记为不发布（{@code publish: false}）的笔记数
 * @param skipped     预留的跳过计数，当前实现恒为 0
 * @param deleted     本次因源文件消失而被软删除的笔记数
 * @param failed      渲染或入库失败的笔记数
 * @param durationMs  索引总耗时（毫秒）
 * @param indexedAt   本次索引完成时间，ISO-8601 字符串
 */
public record IndexResult(
        int scanned,
        int published,
        int unpublished,
        int skipped,
        int deleted,
        int failed,
        long durationMs,
        String indexedAt
) {
}