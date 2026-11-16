package com.noteup.repo;

import com.noteup.model.Comment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 评论数据访问层，负责 {@code comment} 表及其与 {@code app_user} 的联表查询。
 *
 * <p>评论带审核状态（PENDING/APPROVED/REJECTED/DELETED），读取时按可见性规则过滤：
 * 已审核通过的评论对所有人可见，待审核评论仅作者本人可见；管理端则不加可见性限制。</p>
 *
 * <p>所有查询均通过 {@link JdbcTemplate} 参数化执行以避免 SQL 注入；对外返回
 * {@link Comment} 记录，作者用户名由联表取得而非冗余存储。</p>
 */
@Repository
public class CommentRepository {

    /**
     * 评论查询的公共投影：一次取出评论字段与作者用户名。
     *
     * <p>使用 JOIN 而非逐条子查询，避免列表接口出现 N+1 查询；字符串末尾保留空格，
     * 便于各方法直接追加 WHERE/ORDER BY 片段。</p>
     */
    private static final String SELECT = "SELECT c.id, c.note_id, c.user_id, c.parent_id, c.content, "
            + "c.status, c.created_at, u.username FROM comment c JOIN app_user u ON u.id = c.user_id ";

    /**
     * 行映射器：{@code parent_id} 允许为空（顶层评论），因此单独读取并做 null 判断；
     * 状态与时间字段在库中以文本存储，这里统一转换为模型类型。
     */
    private static final RowMapper<Comment> MAPPER = (rs, rowNum) -> new Comment(
            rs.getLong("id"),
            rs.getLong("note_id"),
            rs.getLong("user_id"),
            rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("created_at"),
            rs.getString("username")
    );

    private final JdbcTemplate jdbc;

    /**
     * 构造仓储并注入由 Spring 管理的 JDBC 模板。
     *
     * @param jdbc 已配置 SQLite 数据源的 JDBC 模板
     */
    public CommentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询单条评论，不区分审核状态（管理端与权限校验使用）。
     *
     * @param id 评论主键
     * @return 命中时返回评论，不存在时返回空 Optional
     */
    public Optional<Comment> findById(long id) {
        List<Comment> comments = jdbc.query(SELECT + "WHERE c.id = ?", MAPPER, id);
        return comments.isEmpty() ? Optional.empty() : Optional.of(comments.getFirst());
    }

    /**
     * 新增评论并返回数据库生成的主键。
     *
     * @param noteId    所属笔记 ID
     * @param userId    评论作者 ID
     * @param parentId  父评论 ID，顶层评论为 {@code null}
     * @param content   评论正文（长度与内容已由上层校验）
     * @param status    初始审核状态
     * @param createdAt 创建时间字符串
     * @return 新评论主键
     * @throws IllegalStateException 数据库未返回主键时抛出
     */
    public long insert(long noteId, long userId, Long parentId, String content, String status, String createdAt) {
        Long id = jdbc.queryForObject("INSERT INTO comment (note_id, user_id, parent_id, content, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, noteId, userId, parentId, content, status, createdAt);
        if (id == null) {
            throw new IllegalStateException("Failed to insert comment");
        }
        return id;
    }

    /**
     * 查询某篇笔记下当前访问者可见的评论，按创建时间正序返回（便于前端按楼层构建树）。
     *
     * <p>可见性规则：APPROVED 对所有人可见；PENDING 仅作者本人可见。
     * 匿名访问者以 {@code -1} 代替用户 ID，由于用户主键从 1 开始，自然不会命中任何作者，
     * 从而避免为 null 单独拼一条 SQL。</p>
     *
     * @param noteId   笔记 ID
     * @param viewerId 当前登录用户 ID，匿名时为 {@code null}
     * @return 可见评论列表
     */
    public List<Comment> listVisibleForNote(long noteId, Long viewerId) {
        String sql = SELECT + "WHERE c.note_id = ? AND (c.status = 'APPROVED' "
                + "OR (c.user_id = ? AND c.status = 'PENDING')) ORDER BY c.created_at";
        return jdbc.query(sql, MAPPER, noteId, viewerId == null ? -1L : viewerId);
    }

    /**
     * 管理端分页查询评论，可按审核状态过滤，最新评论在前。
     *
     * <p>以恒真条件 {@code WHERE 1 = 1} 起头，便于按需追加 {@code AND} 片段而无需判断首条件；
     * 过滤值与分页参数全部作为占位符参数绑定。</p>
     *
     * @param status 审核状态过滤，为空表示不过滤
     * @param limit  每页条数
     * @param offset 偏移量
     * @return 评论列表
     */
    public List<Comment> listForAdmin(String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT).append("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND c.status = ? ");
            args.add(status);
        }
        sql.append("ORDER BY c.created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 统计管理端列表总数，过滤条件与 {@link #listForAdmin(String, int, int)} 保持一致。
     *
     * @param status 审核状态过滤，为空表示统计全部
     * @return 满足条件的评论数
     */
    public long countForAdmin(String status) {
        if (status == null || status.isBlank()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM comment", Long.class);
            return count == null ? 0 : count;
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    /**
     * 更新评论审核状态；主键不存在时静默无操作。
     *
     * @param id     评论主键
     * @param status 目标状态
     */
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE comment SET status = ? WHERE id = ?", status, id);
    }

    /**
     * 按状态统计评论数量，用于后台概览。
     *
     * @param status 审核状态
     * @return 评论数
     */
    public long countByStatus(String status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    /**
     * 统计全部评论数量（含待审核、已拒绝与已删除）。
     *
     * @return 评论总数
     */
    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM comment", Long.class);
        return count == null ? 0 : count;
    }
}