#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC_BIN="$(command -v javac || true)"
  if [ -n "$JAVAC_BIN" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC_BIN")")")"
    export JAVA_HOME
  fi
fi

mkdir -p data
export SEED_DEMO_DATA="${SEED_DEMO_DATA:-true}"
exec mvn spring-boot:run