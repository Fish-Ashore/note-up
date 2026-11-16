---
title: SQLite 与 FTS5
slug: sqlite-fts5
publish: true
category: 后端
tags: [sqlite, fts5, 搜索, 数据库]
created: 2026-09-19
updated: 2026-10-24
---

# SQLite 与 FTS5

千篇以内的笔记站，SQLite 足够：单文件、零部署、备份简单。搜索使用 FTS5 虚拟表，查询在毫秒级完成。

## 索引表

```sql
CREATE VIRTUAL TABLE note_fts USING fts5 (
    title,
    content,
    tokenize = 'unicode61 remove_diacritics 2'
);

INSERT INTO note_fts (rowid, title, content)
SELECT id, title, content_raw
FROM note
WHERE publish = 1 AND deleted_at IS NULL;
```

## 中文检索

`unicode61` 会把连续的中文当成一个词，所以查询时使用前缀匹配：

```text
"索引器"*
```

这样能命中「索引器设计」「索引器全量扫描」等文本。英文同样支持前缀搜索。

> [!tip] 重建成本很低
> 每次重索引先清空 FTS 再全量插入。1000 篇以内耗时通常远小于一秒，不需要增量更新。

## 复杂度

设笔记数为 $n$，查询词长度为 $m$，前缀匹配大致为 $O(m + k)$，其中 $k$ 是命中数量。

| 操作 | 预计耗时 |
|---|---|
| 全量重建 | < 1s |
| 关键词搜索 | < 20ms |
| 写入一条评论 | < 10ms |

和 [[索引器设计]] 一起阅读更容易理解整体流程。 #数据库 #搜索