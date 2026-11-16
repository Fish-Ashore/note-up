#!/usr/bin/env bash
set -euo pipefail

VAULT_DIR="${VAULT_DIR:-/srv/vault}"
DB_PATH="${DB_PATH:-/opt/note-up/backend/data/notes.db}"
BACKUP_DIR="${BACKUP_DIR:-/srv/backups/note-up}"
KEEP_DAYS="${KEEP_DAYS:-14}"

STAMP="$(date +%Y%m%d-%H%M%S)"
TARGET="$BACKUP_DIR/$STAMP"
mkdir -p "$TARGET"

tar czf "$TARGET/vault.tar.gz" -C "$(dirname "$VAULT_DIR")" "$(basename "$VAULT_DIR")"

if command -v sqlite3 >/dev/null 2>&1; then
  sqlite3 "$DB_PATH" ".backup '$TARGET/notes.db'"
else
  cp "$DB_PATH" "$TARGET/notes.db"
  [ -f "$DB_PATH-wal" ] && cp "$DB_PATH-wal" "$TARGET/notes.db-wal"
  [ -f "$DB_PATH-shm" ] && cp "$DB_PATH-shm" "$TARGET/notes.db-shm"
fi

find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime "+$KEEP_DAYS" -exec rm -rf {} +

echo "Backup written to $TARGET"