package com.noteup.service;

import com.noteup.model.IndexResult;
// import com.noteup.model.Note;
import com.noteup.model.User;
import com.noteup.repo.NoteRepository;
import com.noteup.repo.UserRepository;
import com.noteup.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 管理后台服务：聚合重建、统计、笔记清单与用户管理。
 *
 * <p>本类是编排层，不直接拼 SQL：统计数字分别来自笔记仓储、评论服务和用户仓储，由这里一次性
 * 组装成 {@link Stats}，避免同一口径在多个控制器里各写一遍。索引重建直接委托
 * {@link VaultIndexer}，因此事务与失败降级策略都沿用索引器本身的约定；访问控制由
 * {@code SecurityConfig} 在接口层统一完成。</p>
 *
 * <p>用户管理是本类唯一承载业务规则的地方：角色白名单、不能操作自己、系统至少保留一个启用的
 * 管理员。把这些约束放在服务层而不是控制器，是为了在任何调用入口下都成立，也便于单元测试。
 * 说明：角色与启用状态是两条独立的 UPDATE，且方法未加事务，并发地降级不同管理员时理论上存在
 * 同时通过的窗口；后台操作频率极低，这里以实现简单为先。</p>
 */
@Service
public class AdminService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final CommentService commentService;
    private final VaultIndexer indexer;

    /**
     * 构造管理服务。
     *
     * @param noteRepository 笔记查询
     * @param userRepository 用户查询与更新
     * @param commentService 评论计数
     * @param indexer        索引器，用于触发重建并读取上次结果
     */
    public AdminService(
        NoteRepository noteRepository,
        UserRepository userRepository,
        CommentService commentService,
        VaultIndexer indexer
    ) {
        this.noteRepository = noteRepository;
        this.userRepository = userRepository;
        this.commentService = commentService;
        this.indexer = indexer;
    }

    /**
     * 触发一次同步重建。
     *
     * <p>重建会全量扫描并渲染 Vault，耗时随文件数增长，调用方（HTTP 接口）需要容忍较长响应；
     * 同一实例内的并发调用会被索引器串行化。</p>
     *
     * @return 本次重建的统计结果
     * @throws ApiException Vault 不可访问时抛出 500
     */
    public IndexResult reindex() {
        return indexer.reindex();
    }

    /**
     * 汇总站点概览数据。
     *
     * <p>所有计数都实时查库，只有“上次索引”直接取索引器的内存快照，因此进程重启后该字段为空，
     * 而 {@code lastIndexedAt} 仍可从数据库恢复。</p>
     *
     * @return 笔记、评论、用户数量及最近索引信息
     */
    public Stats stats() {
        return new Stats(
            noteRepository.countAll(),
            noteRepository.countPublished(),
            noteRepository.countDrafts(),
            noteRepository.countDeleted(),
            commentService.countAll(),
            commentService.count("PENDING"),
            commentService.count("APPROVED"),
            userRepository.countUsers(),
            noteRepository.lastIndexedAt(),
            indexer.lastResult()
        );
    }

    /**
     * 列出全部笔记，包含草稿与已软删除记录。
     *
     * <p>只暴露管理排查所需的字段，不返回正文与渲染结果，避免清单接口响应过大。</p>
     *
     * @return 管理端笔记视图列表
     */
    public List<AdminNote> notes() {
        return noteRepository
            .listAll()
            .stream()
            .map(n ->
                new AdminNote(
                    n.id(),
                    n.path(),
                    n.slug(),
                    n.title(),
                    n.category(),
                    n.publish(),
                    n.deletedAt(),
                    n.updatedAt(),
                    n.indexedAt()
                )
            )
            .toList();
    }

    /**
     * 列出全部用户。
     *
     * @return 用户安全视图列表，不含密码哈希
     */
    public List<User.UserView> users() {
        return userRepository.listAll().stream().map(User::toView).toList();
    }

    /**
     * 修改用户的角色和/或启用状态。
     *
     * <p>参数为 {@code null} 表示“保持不变”，因此可以只改其中一项。安全规则按严重程度依次检查：
     * 先校验角色合法性，再阻止管理员对自己降级或禁用（避免误操作后立即失去后台权限），最后确保
     * 系统不会因此失去最后一个启用的管理员。只有通过全部检查才会真正写库。</p>
     *
     * @param actor    当前操作者，用于识别“操作自己”
     * @param targetId 目标用户 id
     * @param role     新角色，仅接受 {@code ADMIN} 或 {@code GUEST}；{@code null} 表示不修改
     * @param enabled  新启用状态；{@code null} 表示不修改
     * @return 更新后的用户视图
     * @throws ApiException 用户不存在（404）、角色非法（400）、操作自己导致降级/禁用（403）、
     *                      移除最后一个管理员（409）
     */
    public User.UserView updateUser(
        User actor,
        long targetId,
        String role,
        Boolean enabled
    ) {
        User target = userRepository
            .findById(targetId)
            .orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (role != null && !role.equals("ADMIN") && !role.equals("GUEST")) {
            throw ApiException.badRequest("非法角色");
        }
        // 先把缺省值补齐，后续规则统一基于“变更后的最终状态”判断，避免 null 分支散落。
        boolean newEnabled = enabled == null ? target.enabled() : enabled;
        String newRole = role == null ? target.role() : role;

        if (target.id() == actor.id()) {
            if (!newEnabled || !"ADMIN".equals(newRole)) {
                throw ApiException.forbidden("不能禁用或降级当前登录的管理员");
            }
        }
        // 只有“原本是启用管理员、变更后不再是”才算移除；普通用户改角色不受最后管理员规则限制。
        boolean removesAdmin =
            "ADMIN".equals(target.role()) &&
            target.enabled() &&
            (!newEnabled || !"ADMIN".equals(newRole));
        if (removesAdmin && userRepository.countAdmins() <= 1) {
            throw ApiException.conflict("系统至少需要保留一个启用的管理员");
        }

        if (role != null) {
            userRepository.updateRole(targetId, role);
        }
        if (enabled != null) {
            userRepository.updateEnabled(targetId, enabled);
        }
        return userRepository
            .findById(targetId)
            .orElseThrow(() -> ApiException.notFound("用户不存在"))
            .toView();
    }

    /**
     * 管理端笔记视图，保留索引与发布状态以便定位问题。
     *
     * @param id        笔记 id
     * @param path      相对 Vault 根目录的路径
     * @param slug      URL 标识
     * @param title     标题
     * @param category  分类
     * @param publish   是否发布
     * @param deletedAt 软删除时间，未删除时为 {@code null}
     * @param updatedAt 内容更新时间
     * @param indexedAt 最近一次索引时间
     */
    public record AdminNote(
        long id,
        String path,
        String slug,
        String title,
        String category,
        boolean publish,
        String deletedAt,
        String updatedAt,
        String indexedAt
    ) {}

    /**
     * 后台首页统计快照。
     *
     * @param totalNotes       笔记总数（含草稿与已删除）
     * @param publishedNotes   已发布数量
     * @param draftNotes       草稿数量
     * @param deletedNotes     已软删除数量
     * @param totalComments    评论总数
     * @param pendingComments  待审核评论数
     * @param approvedComments 已通过评论数
     * @param totalUsers       用户总数
     * @param lastIndexedAt    数据库中记录的最后索引时间
     * @param lastIndex        本次进程内上次重建的详细结果；尚未重建时为 {@code null}
     */
    public record Stats(
        long totalNotes,
        long publishedNotes,
        long draftNotes,
        long deletedNotes,
        long totalComments,
        long pendingComments,
        long approvedComments,
        long totalUsers,
        String lastIndexedAt,
        IndexResult lastIndex
    ) {}
}
