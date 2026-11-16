package com.noteup.repo;

import com.noteup.model.Note;
import com.noteup.model.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 笔记主表数据访问层，负责 {@code note} 表、其全文索引 {@code note_fts}
 * 以及笔记与标签、链接的关联查询。
 *
 * <p>笔记由文件系统索引器写入：{@code path} 唯一标识源文件，{@code slug} 用于对外 URL，
 * {@code publish} 与 {@code deleted_at} 共同决定公开可见性。只有同时满足
 * {@code publish = 1} 且 {@code deleted_at IS NULL} 的记录才会出现在站点列表与检索结果中。</p>
 *
 * <p>删除采用软删除（写入 {@code deleted_at}）：文件被临时移走再恢复时仍可复用原记录与主键，
 * 避免评论、标签、链接等依赖外键的数据丢失。</p>
 */
@Repository
public class NoteRepository {

    /**
     * 笔记表的完整列清单；带表别名查询时通过 {@code replace(", ", ", n.")} 生成 {@code n.xxx} 前缀。
     * 列顺序必须与 {@link #NOTE_MAPPER} 的读取顺序保持一致。
     */
    private static final String COLUMNS = "id, path, slug, title, category, content_raw, content_html, "
            + "content_hash, publish, deleted_at, created_at, updated_at, indexed_at";

    /**
     * 行映射器：SQLite 以整数 0/1 存储布尔值，故 {@code publish} 在此转换；
     * 时间字段统一以字符串透传，避免引入时区转换带来的歧义。
     */
    private static final RowMapper<Note> NOTE_MAPPER = (rs, rowNum) -> new Note(
            rs.getLong("id"),
            rs.getString("path"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("category"),
            rs.getString("content_raw"),
            rs.getString("content_html"),
            rs.getString("content_hash"),
            rs.getInt("publish") != 0,
            rs.getString("deleted_at"),
            rs.getString("created_at"),
            rs.getString("updated_at"),
            rs.getString("indexed_at")
    );

    private final JdbcTemplate jdbc;

    /**
     * 构造仓储并注入 JDBC 模板。
     *
     * @param jdbc 已配置 SQLite 数据源的 JDBC 模板
     */
    public NoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 slug 查询笔记。结果包含未发布与已删除记录，调用方需自行过滤可见性。
     *
     * @param slug 对外路径标识
     * @return 命中时返回笔记，否则为空 Optional
     */
    public Optional<Note> findBySlug(String slug) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM note WHERE slug = ?", NOTE_MAPPER, slug));
    }

    /**
     * 按主键查询笔记，不区分发布状态。
     *
     * @param id 笔记主键
     * @return 命中时返回笔记，否则为空 Optional
     */
    public Optional<Note> findById(long id) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM note WHERE id = ?", NOTE_MAPPER, id));
    }

    /**
     * 按源文件路径查询笔记，索引器据此判断文件是否已入库。
     *
     * @param path 相对 vault 根目录的文件路径
     * @return 命中时返回笔记，否则为空 Optional
     */
    public Optional<Note> findByPath(String path) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM note WHERE path = ?", NOTE_MAPPER, path));
    }

    /**
     * 只查询 slug 对应的文件路径，避免为一次存在性判断加载整行数据。
     *
     * @param slug 对外路径标识
     * @return 文件路径，不存在时返回 {@code null}
     */
    public String findPathBySlug(String slug) {
        List<String> paths = jdbc.queryForList("SELECT path FROM note WHERE slug = ?", String.class, slug);
        return paths.isEmpty() ? null : paths.getFirst();
    }

    /**
     * 以 {@code path} 为冲突键插入或更新笔记元数据，并返回主键。
     *
     * <p>使用 SQLite 的 {@code ON CONFLICT(path) DO UPDATE} 实现幂等写入：同一文件重复索引时
     * 只更新内容与元数据，保留原主键，从而维持评论、标签、链接等外键关系。</p>
     *
     * <p>更新分支会清空 {@code deleted_at}，使重新出现的文件自动“复活”；但刻意不覆盖
     * {@code content_html} 与 {@code indexed_at}——渲染结果由 {@link #updateRendered}
     * 在渲染成功后单独写入，避免两次写入之间出现 HTML 与原文不一致。</p>
     *
     * @param path        源文件相对路径（唯一键）
     * @param slug        对外路径标识
     * @param title       笔记标题
     * @param category    分类，可为 {@code null}
     * @param contentRaw  Markdown 原文
     * @param contentHash 原文哈希，用于跳过未变化的渲染
     * @param publish     是否发布
     * @param createdAt   创建时间字符串，仅首次插入时生效
     * @param updatedAt   更新时间字符串
     * @return 笔记主键
     * @throws IllegalStateException 数据库未返回主键时抛出
     */
    public long upsert(String path, String slug, String title, String category, String contentRaw,
                       String contentHash, boolean publish, String createdAt, String updatedAt) {
        String sql = """
                INSERT INTO note (path, slug, title, category, content_raw, content_hash, publish,
                                  created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    slug = excluded.slug,
                    title = excluded.title,
                    category = excluded.category,
                    content_raw = excluded.content_raw,
                    content_hash = excluded.content_hash,
                    publish = excluded.publish,
                    deleted_at = NULL,
                    updated_at = excluded.updated_at
                RETURNING id
                """;
        Long id = jdbc.queryForObject(sql, Long.class, path, slug, title, category, contentRaw,
                contentHash, publish ? 1 : 0, createdAt, updatedAt);
        if (id == null) {
            throw new IllegalStateException("Failed to upsert note " + path);
        }
        return id;
    }

    /**
     * 写入渲染后的 HTML、内容哈希与索引完成时间。
     *
     * @param id          笔记主键
     * @param html        渲染结果
     * @param contentHash 与 HTML 对应的原文哈希
     * @param indexedAt   索引完成时间字符串
     */
    public void updateRendered(long id, String html, String contentHash, String indexedAt) {
        jdbc.update("UPDATE note SET content_html = ?, content_hash = ?, indexed_at = ? WHERE id = ?",
                html, contentHash, indexedAt, id);
    }

    /**
     * 清空渲染结果并将索引时间置空，用于源文件解析失败或渲染结果失效时回退展示。
     *
     * @param id 笔记主键
     */
    public void clearRendered(long id) {
        jdbc.update("UPDATE note SET content_html = '', indexed_at = NULL WHERE id = ?", id);
    }

    /**
     * 按路径软删除单篇笔记，同时取消发布。
     *
     * <p>只处理尚未删除的记录（{@code deleted_at IS NULL}），保证首次删除时间不被覆盖。</p>
     *
     * @param path 源文件路径
     * @param now  删除时间字符串
     */
    public void markDeleted(String path, String now) {
        jdbc.update("UPDATE note SET deleted_at = ?, publish = 0 WHERE path = ? AND deleted_at IS NULL",
                now, path);
    }

    /**
     * 把本轮索引中未出现的笔记批量标记为已删除。
     *
     * <p>这是增量索引的“对账”步骤：以本轮扫描到的路径集合为白名单，不在集合中的存活记录
     * 视为文件已被移除。集合为空表示本轮没有扫描到任何文件，此时会删除全部存活记录，
     * 因此调用方必须传入完整扫描结果，而不是部分结果。</p>
     *
     * <p>占位符按集合大小动态生成，路径始终作为参数绑定，不直接拼入 SQL。</p>
     *
     * @param seenPaths 本轮扫描到的文件路径集合
     * @param now       删除时间字符串
     * @return 被标记删除的记录数
     */
    public int markMissing(Collection<String> seenPaths, String now) {
        if (seenPaths.isEmpty()) {
            return jdbc.update("UPDATE note SET deleted_at = ?, publish = 0 WHERE deleted_at IS NULL", now);
        }
        String placeholders = String.join(",", seenPaths.stream().map(p -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(now);
        args.addAll(seenPaths);
        return jdbc.update("UPDATE note SET deleted_at = ?, publish = 0 "
                + "WHERE deleted_at IS NULL AND path NOT IN (" + placeholders + ")", args.toArray());
    }

    /**
     * 重建全文索引：先清空 {@code note_fts}，再写入所有公开笔记的标题与原文。
     *
     * <p>{@code note_fts} 是 FTS5 虚拟表，以 {@code rowid} 关联 {@code note.id}。
     * 只有公开可见的笔记会进入索引，因此检索结果天然不会泄漏草稿或已删除内容；
     * 但全量重建成本较高，通常只在启动或手动重建索引时调用。</p>
     */
    public void rebuildFts() {
        jdbc.update("DELETE FROM note_fts");
        jdbc.update("INSERT INTO note_fts (rowid, title, content) "
                + "SELECT id, title, content_raw FROM note WHERE publish = 1 AND deleted_at IS NULL");
    }

    /**
     * 分页查询公开笔记，支持分类、标签与关键字组合过滤。
     *
     * <p>{@code DISTINCT} 用于消除标签过滤 JOIN 产生的重复行；排序固定为更新时间倒序。
     * 列清单通过字符串替换补上 {@code n.} 前缀，避免与 JOIN 表出现同名列歧义。</p>
     *
     * @param category 分类过滤，为空表示不限
     * @param tag      标签名过滤，为空表示不限
     * @param q        标题/正文关键字（LIKE 模糊匹配），为空表示不限
     * @param limit    每页条数
     * @param offset   偏移量
     * @return 公开笔记列表
     */
    public List<Note> listPublished(String category, String tag, String q, int limit, int offset) {
        QueryParts parts = publicFilter(category, tag, q);
        List<Object> args = parts.args();
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT DISTINCT n." + COLUMNS.replace(", ", ", n.")
                        + " " + parts.fromWhere() + " ORDER BY n.updated_at DESC LIMIT ? OFFSET ?",
                NOTE_MAPPER, args.toArray());
    }

    /**
     * 统计与 {@link #listPublished} 相同过滤条件下的公开笔记总数。
     *
     * @param category 分类过滤，为空表示不限
     * @param tag      标签名过滤，为空表示不限
     * @param q        关键字过滤，为空表示不限
     * @return 笔记总数
     */
    public long countPublished(String category, String tag, String q) {
        QueryParts parts = publicFilter(category, tag, q);
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT n.id) " + parts.fromWhere(),
                Long.class, parts.args().toArray());
        return count == null ? 0 : count;
    }

    /**
     * 构造公开笔记查询的 FROM/WHERE 片段及对应参数。
     *
     * <p>标签过滤需要 JOIN {@code note_tag} 与 {@code tag}，所以 JOIN 片段也在这里统一管理，
     * 确保列表与计数两条 SQL 的条件完全一致。关键字使用 {@code LIKE '%q%'} 仅作轻量筛选，
     * 真正的全文检索走 {@link #search}。</p>
     *
     * @return 不含 SELECT 与 ORDER BY 的 SQL 片段，以及按占位符顺序排列的参数
     */
    private QueryParts publicFilter(String category, String tag, String q) {
        StringBuilder from = new StringBuilder("FROM note n ");
        List<Object> args = new ArrayList<>();
        if (tag != null && !tag.isBlank()) {
            from.append("JOIN note_tag nt ON nt.note_id = n.id JOIN tag t ON t.id = nt.tag_id ");
        }
        from.append("WHERE n.publish = 1 AND n.deleted_at IS NULL ");
        if (category != null && !category.isBlank()) {
            from.append("AND n.category = ? ");
            args.add(category);
        }
        if (tag != null && !tag.isBlank()) {
            from.append("AND t.name = ? ");
            args.add(tag);
        }
        if (q != null && !q.isBlank()) {
            from.append("AND (n.title LIKE ? OR n.content_raw LIKE ?) ");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        return new QueryParts(from.toString(), args);
    }

    /**
     * 列出全部笔记（含草稿与已删除记录），按路径排序，供后台管理使用。
     *
     * @return 全部笔记
     */
    public List<Note> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM note ORDER BY path", NOTE_MAPPER);
    }

    /**
     * 查询指向目标笔记的反向链接来源，仅返回公开可见的来源笔记。
     *
     * <p>只按 {@code target_note_id} 匹配：指向尚不存在笔记的链接不会出现在反链中，
     * 待目标笔记创建并由索引器回填 ID 后才会生效。</p>
     *
     * @param targetNoteId 目标笔记 ID
     * @return 来源笔记列表，按标题排序
     */
    public List<Note> listBacklinks(long targetNoteId) {
        return jdbc.query("SELECT DISTINCT n." + COLUMNS.replace(", ", ", n.") + " FROM link l "
                + "JOIN note n ON n.id = l.source_note_id "
                + "WHERE l.target_note_id = ? AND n.publish = 1 AND n.deleted_at IS NULL "
                + "ORDER BY n.title", NOTE_MAPPER, targetNoteId);
    }

    /**
     * 执行 FTS5 全文检索，返回带高亮片段并按相关度排序的命中结果。
     *
     * <p>关键点：</p>
     * <ul>
     *   <li>{@code MATCH ?} 的查询串由上层 {@code NoteService.toFtsQuery} 负责分词与转义，
     *       本方法只做参数绑定，绝不能把用户原始输入直接拼进 SQL；</li>
     *   <li>{@code snippet(note_fts, 1, ...)} 的第 2 个参数是列序号（0=title，1=content），
     *       因此高亮片段取自正文，省略号长度为 24 个 token；</li>
     *   <li>{@code bm25(note_fts)} 返回越小越相关的分数，因此按升序排列；</li>
     *   <li>JOIN 回主表后再次校验发布状态，防止索引残留导致草稿泄漏。</li>
     * </ul>
     *
     * @param ftsQuery 已转义、符合 FTS5 语法的查询串
     * @param limit    最大返回条数
     * @return 检索命中列表，按相关度升序
     */
    public List<SearchHit> search(String ftsQuery, int limit) {
        String sql = """
                SELECT n.id AS id, n.slug AS slug, n.title AS title, n.category AS category,
                       snippet(note_fts, 1, '<mark>', '</mark>', '…', 24) AS snippet,
                       bm25(note_fts) AS rank
                FROM note_fts
                JOIN note n ON n.id = note_fts.rowid
                WHERE note_fts MATCH ? AND n.publish = 1 AND n.deleted_at IS NULL
                ORDER BY rank
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new SearchHit(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("snippet"),
                rs.getDouble("rank")
        ), ftsQuery, limit);
    }

    /**
     * LIKE 兜底检索，在 FTS 查询语法异常（例如特殊字符导致 MATCH 失败）时使用。
     *
     * <p>返回结构与 {@link #search} 对齐，但 {@code rank} 固定为 0，片段直接截取正文前
     * 160 个字符，因此既无相关度排序也无关键词高亮。{@code like} 参数需由调用方
     * 预先拼好前后通配符。</p>
     *
     * @param like  已带 {@code %} 通配符的匹配串
     * @param limit 最大返回条数
     * @return 检索命中列表，按更新时间倒序
     */
    public List<SearchHit> searchLike(String like, int limit) {
        String sql = """
                SELECT id, slug, title, category, substr(content_raw, 1, 160) AS snippet, 0.0 AS rank
                FROM note
                WHERE publish = 1 AND deleted_at IS NULL
                  AND (title LIKE ? OR content_raw LIKE ?)
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new SearchHit(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("snippet"),
                0.0
        ), like, like, limit);
    }

    /**
     * 列出所有公开笔记的非空分类，去重并排序，供导航栏使用。
     *
     * @return 分类名列表
     */
    public List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM note "
                + "WHERE publish = 1 AND deleted_at IS NULL AND category IS NOT NULL AND category <> '' "
                + "ORDER BY category", String.class);
    }

    /**
     * 统计全部笔记数量（含草稿与已删除）。
     *
     * @return 笔记总数
     */
    public long countAll() {
        return count("SELECT COUNT(*) FROM note");
    }

    /**
     * 统计公开可见的笔记数量（已发布且未删除）。
     *
     * @return 公开笔记数
     */
    public long countPublished() {
        return count("SELECT COUNT(*) FROM note WHERE publish = 1 AND deleted_at IS NULL");
    }

    /**
     * 统计未发布且未删除的草稿数量。
     *
     * @return 草稿数
     */
    public long countDrafts() {
        return count("SELECT COUNT(*) FROM note WHERE publish = 0 AND deleted_at IS NULL");
    }

    /**
     * 统计已软删除的笔记数量，便于后台确认源文件与索引的差异。
     *
     * @return 已删除笔记数
     */
    public long countDeleted() {
        return count("SELECT COUNT(*) FROM note WHERE deleted_at IS NOT NULL");
    }

    /**
     * 返回所有笔记中最新的索引完成时间，用于前端展示“最后同步”状态。
     *
     * @return 最大 {@code indexed_at}，从未索引时返回 {@code null}
     */
    public String lastIndexedAt() {
        List<String> values = jdbc.queryForList(
                "SELECT MAX(indexed_at) FROM note WHERE indexed_at IS NOT NULL", String.class);
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * 执行 COUNT 查询，并把驱动可能返回的 {@code null} 归一为 0。
     *
     * @param sql 只返回单行单列计数的 SQL
     * @return 计数值
     */
    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /**
     * 把查询结果列表转换为 Optional，约定最多取第一条。
     *
     * @param notes 查询结果
     * @return 首条记录，列表为空时返回空 Optional
     */
    private Optional<Note> first(List<Note> notes) {
        return notes.isEmpty() ? Optional.empty() : Optional.of(notes.getFirst());
    }

    /**
     * 公开笔记查询的 SQL 片段与参数载体，避免列表与计数方法重复拼装条件。
     *
     * @param fromWhere 已拼好的 FROM ... WHERE ... 片段
     * @param args      与片段中占位符顺序一致的参数
     */
    private record QueryParts(String fromWhere, List<Object> args) {
    }
}