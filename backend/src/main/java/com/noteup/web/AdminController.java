package com.noteup.web;

import com.noteup.model.Comment;
import com.noteup.model.IndexResult;
import com.noteup.model.User;
import com.noteup.service.AdminService;
import com.noteup.service.CommentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台接口，统一挂载在 {@code /api/admin} 下。
 *
 * <p>鉴权：由 {@code SecurityConfig} 对 {@code /api/admin/**} 统一要求
 * {@code ROLE_ADMIN}，未登录返回 401，非管理员返回 403，因此本类方法内部
 * 不再重复判断角色。</p>
 *
 * <p>错误处理：业务错误通过 {@link ApiException} 抛出，由
 * {@link GlobalExceptionHandler} 转换为携带 {@code message} 字段的 JSON 响应；
 * 例如目标不存在对应 404、非法参数对应 400、移除最后一个管理员对应 409。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 管理统计、笔记列表与用户管理服务。 */
    private final AdminService adminService;
    /** 评论查询与审核服务。 */
    private final CommentService commentService;

    /**
     * 构造控制器。
     *
     * @param adminService   管理后台服务
     * @param commentService 评论服务
     */
    public AdminController(AdminService adminService, CommentService commentService) {
        this.adminService = adminService;
        this.commentService = commentService;
    }

    /**
     * 触发一次全量索引重建，对应 {@code POST /api/admin/reindex}，仅管理员可调用。
     *
     * <p>会重新扫描 Vault、解析前置元数据、渲染 Markdown、重建标签与双链，
     * 并将源文件已消失的笔记标记为软删除；该操作同步执行且耗时与笔记数量相关。</p>
     *
     * @return 本次索引的统计结果（扫描、发布、未发布、删除、失败数量等）
     * @throws ApiException Vault 目录不存在或扫描失败时返回 500
     */
    @PostMapping("/reindex")
    public IndexResult reindex() {
        return adminService.reindex();
    }

    /**
     * 获取后台统计概览，对应 {@code GET /api/admin/stats}，仅管理员可调用。
     *
     * @return 笔记、评论、用户数量，最近索引时间及最近一次索引结果
     */
    @GetMapping("/stats")
    public AdminService.Stats stats() {
        return adminService.stats();
    }

    /**
     * 获取全部笔记（含草稿与已软删除项），对应 {@code GET /api/admin/notes}，仅管理员可调用。
     *
     * @return 后台笔记列表，不分页，按仓库层默认排序返回
     */
    @GetMapping("/notes")
    public List<AdminService.AdminNote> notes() {
        return adminService.notes();
    }

    /**
     * 分页查询评论，对应 {@code GET /api/admin/comments}，仅管理员可调用。
     *
     * <p>结果按创建时间倒序排列，并包含待审核、已拒绝、已删除等所有状态。</p>
     *
     * @param status 可选状态过滤，如 {@code PENDING}、{@code APPROVED}；
     *               为空时不过滤
     * @param page   页码，从 0 开始，默认 0；负数会被服务层归零
     * @param size   每页条数，默认 20；服务层限制在 1 到 100 之间
     * @return 当前页评论及过滤条件下的总条数
     */
    @GetMapping("/comments")
    public PageResponse<Comment.CommentView> comments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return commentService.adminList(status, page, size);
    }

    /**
     * 审核通过指定评论，对应 {@code POST /api/admin/comments/{id}/approve}，仅管理员可调用。
     *
     * @param id 评论主键
     * @return 更新后的评论视图
     * @throws ApiException 评论不存在时返回 404
     */
    @PostMapping("/comments/{id}/approve")
    public Comment.CommentView approve(@PathVariable long id) {
        commentService.setStatus(id, "APPROVED");
        return commentService.get(id);
    }

    /**
     * 拒绝指定评论，对应 {@code POST /api/admin/comments/{id}/reject}，仅管理员可调用。
     *
     * @param id 评论主键
     * @return 更新后的评论视图
     * @throws ApiException 评论不存在时返回 404
     */
    @PostMapping("/comments/{id}/reject")
    public Comment.CommentView reject(@PathVariable long id) {
        commentService.setStatus(id, "REJECTED");
        return commentService.get(id);
    }

    /**
     * 删除指定评论，对应 {@code DELETE /api/admin/comments/{id}}，仅管理员可调用。
     *
     * <p>这里采用状态软删除（置为 {@code DELETED}）而非物理删除，
     * 便于保留回复关系与审计信息；删除后评论不再对普通用户展示。</p>
     *
     * @param id 评论主键
     * @return 更新后的评论视图
     * @throws ApiException 评论不存在时返回 404
     */
    @DeleteMapping("/comments/{id}")
    public Comment.CommentView delete(@PathVariable long id) {
        commentService.setStatus(id, "DELETED");
        return commentService.get(id);
    }

    /**
     * 获取全部用户，对应 {@code GET /api/admin/users}，仅管理员可调用。
     *
     * @return 用户视图列表（不含密码哈希）
     */
    @GetMapping("/users")
    public List<User.UserView> users() {
        return adminService.users();
    }

    /**
     * 修改用户角色或启用状态，对应 {@code PATCH /api/admin/users/{id}}，仅管理员可调用。
     *
     * <p>请求字段均可为空，为空表示保持原值。服务层会额外校验：角色只能是
     * {@code ADMIN} 或 {@code GUEST}；不能禁用或降级当前登录管理员；
     * 系统必须至少保留一个启用的管理员。</p>
     *
     * @param actor   当前登录管理员，由 Spring Security 从 JWT 中解析
     * @param id      目标用户主键
     * @param request 角色与启用状态的可选更新
     * @return 更新后的用户视图
     * @throws ApiException 用户不存在返回 404；角色非法返回 400；
     *                      操作自身或移除最后一个管理员返回 403/409
     */
    @PatchMapping("/users/{id}")
    public User.UserView updateUser(@AuthenticationPrincipal User actor, @PathVariable long id,
                                    @RequestBody UpdateUserRequest request) {
        return adminService.updateUser(actor, id, request.role(), request.enabled());
    }

    /**
     * 用户更新请求体。
     *
     * @param role    新角色，可选值为 {@code ADMIN}、{@code GUEST}；为 null 表示不修改
     * @param enabled 是否启用；为 null 表示不修改
     */
    public record UpdateUserRequest(String role, Boolean enabled) {
    }
}