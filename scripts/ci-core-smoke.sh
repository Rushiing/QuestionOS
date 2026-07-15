#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BACKEND_LOG=${TMPDIR:-/tmp}/questionos-ci-backend.log
FRONTEND_LOG=${TMPDIR:-/tmp}/questionos-ci-frontend.log

cleanup() {
  kill "${FRONTEND_PID:-}" "${BACKEND_PID:-}" 2>/dev/null || true
}
trap cleanup EXIT

cd "$ROOT_DIR/java-backend"
QUESTIONOS_SANDBOX_TOKEN=ci-smoke-token \
  QUESTIONOS_LLM_API_KEY= \
  mvn spring-boot:run >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

wait_for_url() {
  local url=$1
  local log=$2
  for _ in $(seq 1 90); do
    if curl --fail --silent --show-error "$url" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  tail -100 "$log"
  return 1
}

wait_for_url http://127.0.0.1:8080/actuator/health "$BACKEND_LOG"

cd "$ROOT_DIR/v0.2/frontend"
INTERNAL_API_URL=http://127.0.0.1:8080 PORT=3000 npm start >"$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!
wait_for_url http://127.0.0.1:3000/ "$FRONTEND_LOG"

cd "$ROOT_DIR"
QOS_FRONTEND_URL=http://127.0.0.1:3000 \
  QOS_BACKEND_URL=http://127.0.0.1:8080 \
  QOS_SMOKE_ALLOW_MISSING_GOOGLE=1 \
  QOS_SMOKE_TOKEN=ci-smoke-token \
QOS_SMOKE_CREATE_SESSION=1 \
  node scripts/questionos-smoke-check.mjs

QOS_FIXTURE_TOKEN=ci-smoke-token node scripts/questionos-fixtures.mjs seed
QOS_FIXTURE_TOKEN=ci-smoke-token node scripts/questionos-fixtures.mjs status
QOS_FIXTURE_TOKEN=ci-smoke-token node scripts/questionos-fixtures.mjs seed
QOS_FIXTURE_TOKEN=ci-smoke-token node scripts/questionos-fixtures.mjs clean
python3 scripts/questionos-mock-check.py

if QOS_EVAL_URL=https://production.invalid \
  QOS_EVAL_TOKEN=guard-test \
  QOS_EVAL_MODEL=guard-test \
  node scripts/questionos-llm-eval.mjs >/dev/null 2>&1; then
  echo "remote LLM eval guard unexpectedly allowed execution" >&2
  exit 1
fi

if QOS_FIXTURE_URL=https://production.invalid \
  QOS_FIXTURE_TOKEN=guard-test \
  node scripts/questionos-fixtures.mjs seed >/dev/null 2>&1; then
  echo "remote fixture guard unexpectedly allowed execution" >&2
  exit 1
fi
