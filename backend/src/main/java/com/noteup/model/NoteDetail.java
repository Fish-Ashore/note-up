package com.noteup.model;

import java.util.List;

/**
 * 笔记详情视图，供笔记详情接口返回。
 *
 * <p>与数据库实体 {@link Note} 不同，这里只暴露阅读所需字段：Markdown 已渲染为 HTML，
 * 原始内容、内容哈希等内部字段不外泄；{@code tags} 与 {@code backlinks} 由服务层
 * 关联查询后填入。</p>
 *
 * @param id        笔记主键
 * @param slug      对外 URL 标识
 * @param title     标题
 * @param category  分类，可为 {@code null}
 * @param tags      标签列表
 * @param html      渲染后的 HTML 正文
 * @param createdAt 创建时间，来自 frontmatter 或文件创建时间
 * @param updatedAt 更新时间，来自 frontmatter 或文件修改时间
 * @param backlinks 指向本笔记的其他笔记（反向链接）
 */
public record NoteDetail(
        long id,
        String slug,
        String title,
        String category,
        List<String> tags,
        String html,
        String createdAt,
        String updatedAt,
        List<Backlink> backlinks
) {
    /**
     * 反向链接的精简信息，仅够渲染跳转入口。
     *
     * @param slug  来源笔记的 slug
     * @param title 来源笔记的标题
     */
    public record Backlink(String slug, String title) {
    }
}