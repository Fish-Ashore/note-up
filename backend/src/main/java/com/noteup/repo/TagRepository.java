package com.noteup.repo;

import com.noteup.model.TagCount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 标签数据访问层，负责 {@code tag} 与 {@code note_tag} 两张表。
 *
 * <p>标签按名称全局唯一，笔记与标签是多对多关系。索引重建时先清空某篇笔记的关联，
 * 再逐条附加；统计接口只计入公开可见的笔记，因此草稿上的标签不会出现在标签云中。</p>
 */
@Repository
public class TagRepository {

    private final JdbcTemplate jdbc;

    /**
     * 构造仓储并注入 JDBC 模板。
     *
     * @param jdbc 已配置 SQLite 数据源的 JDBC 模板
     */
    public TagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 删除某篇笔记的全部标签关联，但不删除标签本身（标签可能被其他笔记复用）。
     *
     * @param noteId 笔记 ID
     */
    public void clearForNote(long noteId) {
        jdbc.update("DELETE FROM note_tag WHERE note_id = ?", noteId);
    }

    /**
     * 为笔记附加标签，标签不存在时自动创建。
     *
     * <p>两步写入均使用 {@code INSERT OR IGNORE} 保证幂等：重复索引或并发写入时不会因唯一约束报错。
     * 先插入再按名称回查主键，是因为 SQLite 的 {@code RETURNING} 在命中冲突而不插入时不会返回既有行。</p>
     *
     * @param noteId 笔记 ID
     * @param name   标签名称（调用方应已去除首尾空白）
     */
    public void attach(long noteId, String name) {
        jdbc.update("INSERT OR IGNORE INTO tag (name) VALUES (?)", name);
        Long tagId = jdbc.queryForObject("SELECT id FROM tag WHERE name = ?", Long.class, name);
        if (tagId != null) {
            jdbc.update("INSERT OR IGNORE INTO note_tag (note_id, tag_id) VALUES (?, ?)", noteId, tagId);
        }
    }

    /**
     * 查询某篇笔记的标签名，按名称排序。
     *
     * @param noteId 笔记 ID
     * @return 标签名列表
     */
    public List<String> listForNote(long noteId) {
        return jdbc.queryForList("SELECT t.name FROM tag t "
                + "JOIN note_tag nt ON nt.tag_id = t.id WHERE nt.note_id = ? ORDER BY t.name",
                String.class, noteId);
    }

    /**
     * 统计每个标签下公开笔记的数量，用于标签云。
     *
     * <p>{@code COUNT(DISTINCT n.id)} 避免同一笔记因关联异常被重复计数；
     * 排序先按数量倒序、再按名称升序，保证数量相同时结果稳定。</p>
     *
     * @return 标签及其对应的公开笔记数
     */
    public List<TagCount> listCounts() {
        return jdbc.query("""
                SELECT t.name AS name, COUNT(DISTINCT n.id) AS cnt
                FROM tag t
                JOIN note_tag nt ON nt.tag_id = t.id
                JOIN note n ON n.id = nt.note_id
                WHERE n.publish = 1 AND n.deleted_at IS NULL
                GROUP BY t.name
                ORDER BY cnt DESC, t.name
                """, (rs, rowNum) -> new TagCount(rs.getString("name"), rs.getLong("cnt")));
    }

    /**
     * 清理没有任何笔记关联的孤立标签，避免标签表随索引反复重建而膨胀。
     *
     * <p>子查询对 {@code note_tag.tag_id} 去重后再比较，即使关联表很大也只扫描一次；
     * 依赖外键级联删除后，孤立标签不会自动消失，故需要显式清理。</p>
     */
    public void cleanupOrphans() {
        jdbc.update("DELETE FROM tag WHERE id NOT IN (SELECT DISTINCT tag_id FROM note_tag)");
    }
}