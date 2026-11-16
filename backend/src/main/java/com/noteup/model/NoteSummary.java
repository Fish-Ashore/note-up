package com.noteup.model;

import java.util.List;

/**
 * 笔记列表项视图，用于列表页、分类/标签筛选与分页接口。
 *
 * <p>相比 {@link NoteDetail} 刻意省略正文 HTML，改用纯文本摘要，避免列表接口返回
 * 大量数据；{@code tags} 与 {@code excerpt} 均由服务层额外查询或计算后填入。</p>
 *
 * @param id        笔记主键
 * @param slug      对外 URL 标识
 * @param title     标题
 * @param category  分类，可为 {@code null}
 * @param tags      标签列表
 * @param updatedAt 更新时间，列表默认按此倒序排列
 * @param excerpt   从 Markdown 提取的纯文本摘要，最长约 180 字
 */
public record NoteSummary(
        long id,
        String slug,
        String title,
        String category,
        List<String> tags,
        String updatedAt,
        String excerpt
) {
}