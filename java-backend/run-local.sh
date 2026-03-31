#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [[ -f ".env.local" ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^\s*#' .env.local | grep -v '^\s*$' | xargs -0 2>/dev/null || true)
  # Fallback for BSD grep/xargs: parse line-by-line
  while IFS= read -r line; do
    [[ -z "${line// }" ]] && continue
    [[ "${line:0:1}" == "#" ]] && continue
    export "$line"
  done < <(grep -v '^\s*#' .env.local | grep -v '^\s*$' || true)
fi

echo "QUESTIONOS_LLM_ENDPOINT=${QUESTIONOS_LLM_ENDPOINT:-<empty>}"
echo "QUESTIONOS_LLM_MODEL=${QUESTIONOS_LLM_MODEL:-<empty>}"
echo "QUESTIONOS_LLM_API_KEY=<hidden>"

mvn -DskipTests spring-boot:run

