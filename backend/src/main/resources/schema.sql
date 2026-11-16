CREATE TABLE IF NOT EXISTS note (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    path         TEXT    NOT NULL UNIQUE,
    slug         TEXT    NOT NULL UNIQUE,
    title        TEXT    NOT NULL,
    category     TEXT,
    content_raw  TEXT    NOT NULL DEFAULT '',
    content_html TEXT    NOT NULL DEFAULT '',
    content_hash TEXT,
    publish      INTEGER NOT NULL DEFAULT 0,
    deleted_at   TEXT,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL,
    indexed_at   TEXT
);

CREATE INDEX IF NOT EXISTS idx_note_publish ON note (publish, deleted_at);
CREATE INDEX IF NOT EXISTS idx_note_category ON note (category);

CREATE TABLE IF NOT EXISTS tag (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT    NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS note_tag (
    note_id INTEGER NOT NULL,
    tag_id  INTEGER NOT NULL,
    PRIMARY KEY (note_id, tag_id),
    FOREIGN KEY (note_id) REFERENCES note (id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_note_tag_tag ON note_tag (tag_id);

CREATE TABLE IF NOT EXISTS link (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    source_note_id INTEGER NOT NULL,
    target_title   TEXT    NOT NULL,
    target_note_id INTEGER,
    FOREIGN KEY (source_note_id) REFERENCES note (id) ON DELETE CASCADE,
    FOREIGN KEY (target_note_id) REFERENCES note (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_link_source ON link (source_note_id);
CREATE INDEX IF NOT EXISTS idx_link_target ON link (target_note_id);

CREATE TABLE IF NOT EXISTS app_user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,
    role          TEXT    NOT NULL DEFAULT 'GUEST' CHECK (role IN ('ADMIN', 'GUEST')),
    enabled       INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS comment (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    note_id    INTEGER NOT NULL,
    user_id    INTEGER NOT NULL,
    parent_id  INTEGER,
    content    TEXT    NOT NULL,
    status     TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'DELETED')),
    created_at TEXT    NOT NULL,
    FOREIGN KEY (note_id) REFERENCES note (id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES comment (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_comment_note ON comment (note_id, status);
CREATE INDEX IF NOT EXISTS idx_comment_status ON comment (status, created_at);

CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5 (
    title,
    content,
    tokenize = 'unicode61 remove_diacritics 2'
);