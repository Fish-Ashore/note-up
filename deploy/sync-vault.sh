#!/usr/bin/env bash
set -euo pipefail

# Run on the machine that holds the Obsidian vault.
# Required: SERVER (e.g. noteup@example.com), HOOK_TOKEN
# Optional: VAULT_DIR, REMOTE_ROOT, REMOTE_STAGE, API_URL
VAULT_DIR="${VAULT_DIR:-$HOME/vault}"
SERVER="${SERVER:?Set SERVER=user@host}"
HOOK_TOKEN="${HOOK_TOKEN:?Set HOOK_TOKEN to the backend hook token}"
REMOTE_ROOT="${REMOTE_ROOT:-/srv/vault}"
REMOTE_STAGE="${REMOTE_STAGE:-/srv/vault-staging}"
API_URL="${API_URL:-http://localhost:8080}"

rsync -az --delete \
  --exclude='.obsidian/' \
  --exclude='.trash/' \
  --exclude='.git/' \
  "$VAULT_DIR/" "$SERVER:$REMOTE_STAGE/"

ssh "$SERVER" "set -euo pipefail
  rm -rf '${REMOTE_ROOT}.old'
  if [ -e '${REMOTE_ROOT}' ]; then mv '${REMOTE_ROOT}' '${REMOTE_ROOT}.old'; fi
  mv '${REMOTE_STAGE}' '${REMOTE_ROOT}'
  rm -rf '${REMOTE_ROOT}.old'
  curl -sf -X POST '${API_URL}/api/hooks/reindex' -H 'X-Hook-Token: ${HOOK_TOKEN}'
"

echo "Vault synced and reindex triggered."