package com.noteup.web;

import com.noteup.model.Comment;
import com.noteup.model.User;
import com.noteup.service.CommentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评论接口，包含公开读取与登录后发表两类能力。
 *
 * <p>鉴权：{@code GET /api/notes/{slug}/comments} 允许匿名访问，但登录用户
 * 会额外看到自己待审核的评论；{@code POST /api/comments} 要求已登录，
 * 未登录时安全配置返回 401，本类再做一次判空兜底。</p>
 *
 * <p>错误处理：笔记不存在返回 404；内容为空、超长、回复目标非法返回 400；
 * 触发评论频率限制返回 429，均由 {@link GlobalExceptionHandler} 统一渲染。</p>
 */
@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;

    /**
     * 构造控制器。
     *
     * @param commentService 评论服务
     */
    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 获取指定笔记的评论树，对应 {@code GET /api/notes/{slug}/comments}，允许匿名访问。
     *
     * <p>仅返回已通过审核的评论；若请求携带有效 JWT，则额外返回当前用户自己
     * 待审核的评论，便于用户看到刚发表的内容。回复会被组装为两层树结构。</p>
     *
     * @param slug 笔记的 URL 标识
     * @param user 当前登录用户，匿名访问时为 null
     * @return 根评论列表，每条评论的 {@code replies} 中包含其回复
     * @throws ApiException 笔记不存在、未发布或已删除时返回 404
     */
    @GetMapping("/notes/{slug}/comments")
    public List<Comment.CommentView> list(@PathVariable String slug,
                                          @AuthenticationPrincipal User user) {
        return commentService.listForNote(slug, user == null ? null : user.id());
    }

    /**
     * 发表评论或回复，对应 {@code POST /api/comments}，需要登录。
     *
     * <p>新评论统一进入 {@code PENDING} 状态等待管理员审核；若提供
     * {@code parentId}，服务层会校验父评论属于同一篇已发布笔记、未被删除或拒绝，
     * 且待审核的父评论仅作者本人或管理员可回复。发表前还会执行按用户维度的
     * 频率限制。</p>
     *
     * @param user    当前登录用户，由 JWT 解析
     * @param request 笔记 slug、评论内容和可选的父评论 ID
     * @return 新建评论的视图
     * @throws ApiException 未登录返回 401；参数或回复目标非法返回 400；
     *                      笔记不存在返回 404；发表过于频繁返回 429
     */
    @PostMapping("/comments")
    public Comment.CommentView create(@AuthenticationPrincipal User user,
                                      @RequestBody CreateCommentRequest request) {
        if (user == null) {
            throw ApiException.unauthorized("请先登录");
        }
        return commentService.create(user, request.slug(), request.content(), request.parentId());
    }

    /**
     * 发表评论请求体。
     *
     * @param slug     目标笔记的 URL 标识，必须对应已发布笔记
     * @param content  评论正文，去空白后不能为空且不超过配置的最大长度
     * @param parentId 可选父评论 ID；为 null 表示发表根评论
     */
    public record CreateCommentRequest(String slug, String content, Long parentId) {
    }
}