#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/java-backend"
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  echo "Loaded java-backend/.env"
else
  echo "Warning: java-backend/.env missing; LLM calls may fail."
fi
exec mvn -q spring-boot:run
