#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

cleanup() {
  kill "${BACKEND_PID:-}" "${FRONTEND_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

(
  cd backend
  ./run.sh
) &
BACKEND_PID=$!

(
  cd frontend
  if [ ! -d node_modules ]; then
    npm install
  fi
  npm run dev
) &
FRONTEND_PID=$!

echo "Backend:  http://localhost:8080"
echo "Frontend: http://localhost:5173"
wait