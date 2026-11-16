package com.noteup.model;

/**
 * 笔记实体，对应 {@code note} 表中的一行，是索引器的核心存储模型。
 *
 * <p>{@code path} 是相对 Vault 根目录的稳定标识，{@code slug} 用于对外 URL。
 * 源文件消失时不做物理删除，而是写入 {@code deletedAt} 进行软删除，既保留评论外键，
 * 也便于文件恢复后复用原记录。原始 Markdown 与渲染后的 HTML 同时保存，前者用于重建
 * 搜索/摘要，后者直接用于详情页展示。</p>
 *
 * @param id          笔记主键
 * @param path        相对 Vault 根目录的文件路径
 * @param slug        对外 URL 标识，冲突时由索引器重新分配
 * @param title       标题，frontmatter 未指定时取文件名
 * @param category    分类，可为 {@code null}
 * @param contentRaw  去除 frontmatter 后的 Markdown 正文
 * @param contentHtml 渲染后的 HTML；渲染失败时为空
 * @param contentHash 正文的 SHA-256，用于识别内容是否变化
 * @param publish     是否发布；未显式声明时默认 {@code false}
 * @param deletedAt   软删除时间；{@code null} 表示源文件仍存在
 * @param createdAt   创建时间，取自 frontmatter 或文件创建时间
 * @param updatedAt   更新时间，取自 frontmatter 或文件修改时间
 * @param indexedAt   最近一次成功渲染并写回的时间，可为 {@code null}
 */
public record Note(
        long id,
        String path,
        String slug,
        String title,
        String category,
        String contentRaw,
        String contentHtml,
        String contentHash,
        boolean publish,
        String deletedAt,
        String createdAt,
        String updatedAt,
        String indexedAt
) {
}