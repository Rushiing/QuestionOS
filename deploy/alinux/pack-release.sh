#!/usr/bin/env bash
# 在开发机（macOS/Linux）项目根目录执行：生成可上传的 tar 包
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PUBLIC_API_URL="${PUBLIC_API_URL:-http://47.253.98.164}"
SANDBOX_TOKEN="${SANDBOX_TOKEN:-REPLACE_ME_SANDBOX_TOKEN}"

echo "==> Maven package (java-backend)"
(cd java-backend && mvn -q -DskipTests package)

echo "==> Next.js build (v0.2/frontend)"
(
  cd v0.2/frontend
  export NEXT_PUBLIC_API_URL="$PUBLIC_API_URL"
  export INTERNAL_API_URL="http://127.0.0.1:8080"
  export NEXT_PUBLIC_SANDBOX_TOKEN="$SANDBOX_TOKEN"
  npm run build
)

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/frontend"
cp java-backend/target/java-backend-0.1.0.jar "$STAGE/"
cp -R v0.2/frontend/.next v0.2/frontend/public "$STAGE/frontend/"
cp v0.2/frontend/package.json v0.2/frontend/package-lock.json v0.2/frontend/next.config.js "$STAGE/frontend/"
mkdir -p "$STAGE/deploy/alinux"
cp deploy/alinux/install-server.sh deploy/alinux/nginx-questionos.conf \
  deploy/alinux/questionos-backend.service deploy/alinux/questionos-frontend.service \
  deploy/alinux/backend.env.example deploy/alinux/README.md deploy/alinux/STEP-BY-STEP.md \
  deploy/alinux/rollback.sh \
  "$STAGE/deploy/alinux/"

mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/questionos-release-$(date +%Y%m%d-%H%M).tar.gz"
tar -czf "$OUT" -C "$STAGE" .
echo "==> OK: $OUT"
