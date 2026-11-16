package com.noteup.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 笔记双链（wiki link）数据访问层，对应 {@code link} 表。
 *
 * <p>索引器解析 Markdown 时，会把 {@code [[目标标题]]} 拆成一条记录：
 * {@code source_note_id} 指向来源笔记，{@code target_title} 保存书写时的原始标题，
 * {@code target_note_id} 在目标笔记存在时回填。反向链接查询依赖 {@code target_note_id}，
 * 因此目标笔记新建或改名后需要重建链接关系。</p>
 */
@Repository
public class LinkRepository {

    private final JdbcTemplate jdbc;

    /**
     * 构造仓储并注入 JDBC 模板。
     *
     * @param jdbc 已配置 SQLite 数据源的 JDBC 模板
     */
    public LinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 删除某篇笔记的全部出链记录，通常在重建该笔记索引前先清空旧数据。
     *
     * @param sourceNoteId 来源笔记 ID
     */
    public void clearForSource(long sourceNoteId) {
        jdbc.update("DELETE FROM link WHERE source_note_id = ?", sourceNoteId);
    }

    /**
     * 写入一条出链记录。
     *
     * @param sourceNoteId 来源笔记 ID
     * @param targetTitle  链接中书写的目标标题（未解析）
     * @param targetNoteId 解析到的目标笔记 ID，目标尚不存在时为 {@code null}
     */
    public void insert(long sourceNoteId, String targetTitle, Long targetNoteId) {
        jdbc.update("INSERT INTO link (source_note_id, target_title, target_note_id) VALUES (?, ?, ?)",
                sourceNoteId, targetTitle, targetNoteId);
    }

    /**
     * 查询某篇笔记的全部出链目标标题，供编辑态展示或与新一轮解析结果比对。
     *
     * @param sourceNoteId 来源笔记 ID
     * @return 目标标题列表，顺序不做保证
     */
    public List<String> targetTitles(long sourceNoteId) {
        return jdbc.queryForList("SELECT target_title FROM link WHERE source_note_id = ?",
                String.class, sourceNoteId);
    }
}