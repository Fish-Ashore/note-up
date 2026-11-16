package com.noteup.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论实体，对应 {@code comment} 表中的一行。
 *
 * <p>评论通过 {@code parentId} 组成树形结构，数据库中只存平铺记录，由服务层组装成
 * {@link CommentView} 后返回前端。{@code username} 不属于 comment 表，是查询时联表
 * 带出的展示字段。</p>
 *
 * @param id        评论主键
 * @param noteId    所属笔记 ID
 * @param userId    发表者用户 ID
 * @param parentId  父评论 ID；{@code null} 表示顶层评论
 * @param content   评论正文（纯文本）
 * @param status    审核状态：PENDING / APPROVED / REJECTED / DELETED
 * @param createdAt 创建时间，ISO-8601 字符串
 * @param username  发表者用户名，由联表查询得到
 */
public record Comment(
        long id,
        long noteId,
        long userId,
        Long parentId,
        String content,
        String status,
        String createdAt,
        String username
) {
    /**
     * 转换为前端展示用的视图对象。
     *
     * <p>回复列表初始化为可变集合，便于调用方随后逐条挂接子评论。</p>
     *
     * @return 尚未填充回复的评论视图
     */
    public CommentView toView() {
        return new CommentView(id, noteId, userId, parentId, content, status, createdAt, username, new ArrayList<>());
    }

    /**
     * 评论树节点视图：在评论实体基础上附带子回复。
     *
     * @param id        评论主键
     * @param noteId    所属笔记 ID
     * @param userId    发表者用户 ID
     * @param parentId  父评论 ID；{@code null} 表示顶层评论
     * @param content   评论正文
     * @param status    审核状态
     * @param createdAt 创建时间
     * @param username  发表者用户名
     * @param replies   子回复列表，按创建时间升序；叶子节点为空列表
     */
    public record CommentView(
            long id,
            long noteId,
            long userId,
            Long parentId,
            String content,
            String status,
            String createdAt,
            String username,
            List<CommentView> replies
    ) {
    }
}