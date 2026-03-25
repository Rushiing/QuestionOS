#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="${TOKEN:-sk-sandbox-dev}"

echo "1) register third-party agent"
curl -sS -X POST "${BASE_URL}/api/v1/agents/register" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "agentId":"partner-demo",
    "provider":"OpenClaw",
    "endpoint":"https://partner.example.com/invoke",
    "scope":"sandbox:invoke"
  }' | jq .

echo "2) capabilities"
curl -sS "${BASE_URL}/api/v1/agents/capabilities" \
  -H "Authorization: Bearer ${TOKEN}" | jq .

echo "3) invoke"
curl -sS -X POST "${BASE_URL}/api/v1/agents/partner-demo/invoke" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"sess_demo","input":"hello"}' | jq .
