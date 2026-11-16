package com.noteup.service;

import com.noteup.config.AppProperties;
import com.noteup.model.Comment;
import com.noteup.model.Note;
import com.noteup.model.User;
import com.noteup.repo.CommentRepository;
import com.noteup.repo.NoteRepository;
import com.noteup.web.ApiException;
import com.noteup.web.PageResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论服务：负责评论的公开读取、发表与后台审核。
 *
 * <p><b>数据结构。</b>评论表只有 {@code parent_id} 一列，存储上刻意保持“两层楼层”模型：顶层评论
 * {@code parent_id} 为空，所有回复无论回复的是谁，都把 {@code parent_id} 归一到楼层根节点。
 * 这样既符合博客常见的展示方式，也避免递归查询；树结构由 {@link #buildTree} 在内存中拼装。</p>
 *
 * <p><b>审核与可见性。</b>新评论一律以 {@code PENDING} 落库，公开接口只返回已审核通过的评论，
 * 但允许作者看到自己待审核的内容，让用户获得“已提交”的反馈。审核动作（APPROVED/REJECTED/DELETED）
 * 由管理端触发，删除采用状态标记而不是物理删除，以保留楼层与回复关系。</p>
 *
 * <p><b>防滥用。</b>发表前经过 {@link CommentRateLimiter} 的滑动窗口限流（每分钟与每天两个维度）。
 * 限流状态保存在进程内存中，实现简单、无额外依赖，代价是多实例部署时各自计数、重启后清零，
 * 对单机小型站点是可接受的取舍。</p>
 */
@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final NoteRepository noteRepository;
    private final CommentRateLimiter rateLimiter;
    private final AppProperties properties;

    /**
     * 构造评论服务。
     *
     * @param commentRepository 评论持久化
     * @param noteRepository    笔记查询，用于校验评论目标是否公开
     * @param rateLimiter       发表限流器
     * @param properties        提供评论长度等配置
     */
    public CommentService(CommentRepository commentRepository, NoteRepository noteRepository,
                          CommentRateLimiter rateLimiter, AppProperties properties) {
        this.commentRepository = commentRepository;
        this.noteRepository = noteRepository;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * 查询某篇已发布笔记的可见评论，并组装成两级楼层。
     *
     * @param slug     笔记 slug
     * @param viewerId 当前登录用户 id；匿名访问时为 {@code null}，只返回已通过审核的评论
     * @return 顶层评论列表，回复挂在 {@code replies} 中
     * @throws ApiException 笔记不存在、未发布或已删除时抛出 404
     */
    public List<Comment.CommentView> listForNote(String slug, Long viewerId) {
        Note note = publishedNote(slug);
        return buildTree(commentRepository.listVisibleForNote(note.id(), viewerId));
    }

    /**
     * 发表评论或回复。
     *
     * <p>校验顺序是先内容与目标、再限流、最后写库：非法请求不应消耗用户的发表配额。回复时会把
     * 目标归一到楼层根节点，并复核根节点状态——被拒绝/删除的楼层不可再回复，未公开的楼层只有
     * 作者本人或管理员可以继续回复，防止通过回复 id 探测隐藏内容。</p>
     *
     * <p>写入状态固定为 {@code PENDING}，即“先审后发”。插入后重新读取一次，是为了拿到数据库
     * 生成的 id 与时间戳，保证返回给前端的数据与后续查询完全一致。</p>
     *
     * @param user    发表者
     * @param slug    目标笔记 slug
     * @param content 评论正文，允许首尾空白，入库前裁剪
     * @param parentId 被回复的评论 id；为 {@code null} 表示发表顶层评论
     * @return 新建评论的视图
     * @throws ApiException 内容为空或超长（400）、笔记不存在（404）、父评论非法（400）、触发限流（429）
     */
    public Comment.CommentView create(User user, String slug, String content, Long parentId) {
        Note note = publishedNote(slug);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("评论内容不能为空");
        }
        if (text.length() > properties.getComments().getMaxLength()) {
            throw ApiException.badRequest("评论不能超过 " + properties.getComments().getMaxLength() + " 字");
        }

        Long rootParentId = null;
        if (parentId != null) {
            Comment parent = commentRepository.findById(parentId)
                    .orElseThrow(() -> ApiException.badRequest("回复的评论不存在"));
            if (parent.noteId() != note.id()) {
                throw ApiException.badRequest("回复的评论不属于该笔记");
            }
            // 回复“回复”时也挂到楼层根，存储层永远只有两层，展示层无需递归。
            long rootId = parent.parentId() == null ? parent.id() : parent.parentId();
            Comment root = commentRepository.findById(rootId)
                    .orElseThrow(() -> ApiException.badRequest("回复的评论不存在"));
            if ("DELETED".equals(root.status()) || "REJECTED".equals(root.status())) {
                throw ApiException.badRequest("该评论已不可回复");
            }
            if ("PENDING".equals(root.status())
                    && root.userId() != user.id() && !"ADMIN".equals(user.role())) {
                throw ApiException.badRequest("该评论尚未公开");
            }
            rootParentId = rootId;
        }

        // 放在所有校验之后：只有真正可能落库的请求才占用配额，避免恶意构造请求耗尽自己的额度。
        if (!rateLimiter.tryAcquire(user.id())) {
            throw ApiException.tooMany("评论太频繁，请稍后再试");
        }

        long id = commentRepository.insert(note.id(), user.id(), rootParentId, text, "PENDING",
                Instant.now().toString());
        return commentRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Comment disappeared after insert"))
                .toView();
    }

    /**
     * 管理端分页查询评论，可按审核状态过滤。
     *
     * @param status 审核状态；为空表示全部
     * @param page   页码，从 0 开始，负数按 0 处理
     * @param size   每页条数，限制在 1–100
     * @return 当前页评论及总数
     */
    public PageResponse<Comment.CommentView> adminList(String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Comment.CommentView> items = commentRepository
                .listForAdmin(status, safeSize, safePage * safeSize).stream()
                .map(Comment::toView)
                .toList();
        return new PageResponse<>(items, commentRepository.countForAdmin(status), safePage, safeSize);
    }

    /**
     * 更新评论状态，用于审核通过、拒绝与逻辑删除。
     *
     * <p>先确认评论存在，让不存在的 id 得到明确的 404，而不是静默更新 0 行。状态值由管理端固定
     * 传入（APPROVED/REJECTED/DELETED），本方法不再做白名单校验。</p>
     *
     * @param id     评论 id
     * @param status 目标状态
     * @throws ApiException 评论不存在时抛出 404
     */
    public void setStatus(long id, String status) {
        commentRepository.findById(id).orElseThrow(() -> ApiException.notFound("评论不存在"));
        commentRepository.updateStatus(id, status);
    }

    /**
     * 按 id 读取单条评论。
     *
     * @param id 评论 id
     * @return 评论视图，不含回复
     * @throws ApiException 评论不存在时抛出 404
     */
    public Comment.CommentView get(long id) {
        return commentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("评论不存在"))
                .toView();
    }

    /**
     * 统计指定状态的评论数。
     *
     * @param status 审核状态
     * @return 数量
     */
    public long count(String status) {
        return commentRepository.countByStatus(status);
    }

    /**
     * 统计全部评论数。
     *
     * @return 数量
     */
    public long countAll() {
        return commentRepository.countAll();
    }

    /**
     * 校验笔记存在、已发布且未被删除。
     *
     * <p>评论必须依附于公开内容，否则会变成绕过笔记可见性的旁路。</p>
     *
     * @param slug 笔记 slug
     * @return 已发布的笔记
     * @throws ApiException 不满足可见性条件时抛出 404
     */
    private Note publishedNote(String slug) {
        return noteRepository.findBySlug(slug)
                .filter(n -> n.publish() && n.deletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("笔记不存在"));
    }

    /**
     * 把扁平的评论列表组装成两级楼层。
     *
     * <p>分两趟完成：第一趟建 id 索引并挑出顶层评论（保持仓储返回的创建时间顺序）；第二趟挂接回复。
     * 由于可见性过滤可能让父评论不可见（例如父评论待审核、回复已通过），这里对“父节点不在结果中”
     * 的情况做了兜底——把该回复提升为顶层，避免评论凭空消失。</p>
     *
     * @param comments 已过滤的扁平评论列表，按创建时间升序
     * @return 顶层评论列表
     */
    private List<Comment.CommentView> buildTree(List<Comment> comments) {
        Map<Long, Comment.CommentView> views = new LinkedHashMap<>();
        List<Comment.CommentView> roots = new ArrayList<>();
        for (Comment comment : comments) {
            Comment.CommentView view = comment.toView();
            views.put(view.id(), view);
            if (comment.parentId() == null) {
                roots.add(view);
            }
        }
        for (Comment comment : comments) {
            if (comment.parentId() == null) {
                continue;
            }
            Comment.CommentView view = views.get(comment.id());
            Comment.CommentView parent = views.get(comment.parentId());
            if (parent == null) {
                roots.add(view);
            } else if (parent.parentId() == null) {
                parent.replies().add(view);
            } else {
                Comment.CommentView root = views.get(parent.parentId());
                if (root == null) {
                    roots.add(view);
                } else {
                    root.replies().add(view);
                }
            }
        }
        return roots;
    }
}