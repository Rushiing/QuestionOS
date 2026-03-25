#!/usr/bin/env bash
# 一键回滚到 /opt/questionos/releases/<时间戳>/ 里备份的“上一版”
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "请使用 root 执行：sudo $0 [releaseId]"
  exit 1
fi

INSTALL_ROOT=/opt/questionos
RELEASES_ROOT="$INSTALL_ROOT/releases"
CURRENT_JAR="$INSTALL_ROOT/java-backend-current.jar"
LEGACY_JAR="$INSTALL_ROOT/java-backend-0.1.0.jar"
FRONTEND_ROOT="$INSTALL_ROOT/frontend"

TARGET_ID="${1:-}"

if [[ -z "$TARGET_ID" ]]; then
  if [[ -f "$INSTALL_ROOT/LAST_BACKUP_ID" ]]; then
    TARGET_ID="$(cat "$INSTALL_ROOT/LAST_BACKUP_ID" | tr -d '[:space:]')"
  fi
fi

if [[ -z "$TARGET_ID" ]]; then
  # 兜底：取 releases 里最新的一个备份目录
  latest="$(ls -1dt "$RELEASES_ROOT"/* 2>/dev/null | head -n 1 || true)"
  if [[ -n "$latest" ]]; then
    TARGET_ID="$(basename "$latest")"
  fi
fi

BACKUP_DIR="$RELEASES_ROOT/$TARGET_ID"
if [[ -z "$TARGET_ID" || ! -d "$BACKUP_DIR" ]]; then
  echo "未找到可回滚的备份目录：$BACKUP_DIR"
  echo "你可以先查看：ls -ლა $RELEASES_ROOT"
  exit 1
fi

echo "==> 回滚到：$TARGET_ID"

echo "==> 停止服务"
systemctl stop questionos-frontend questionos-backend 2>/dev/null || true

echo "==> 恢复后端"
if [[ -f "$BACKUP_DIR/backend/java-backend-current.jar" ]]; then
  install -o questionos -g questionos -m 0644 "$BACKUP_DIR/backend/java-backend-current.jar" "$CURRENT_JAR"
  # 兼容旧版 systemd unit（若当前 ExecStart 仍指向固定文件名）
  if [[ -f "$LEGACY_JAR" ]]; then
    install -o questionos -g questionos -m 0644 "$BACKUP_DIR/backend/java-backend-current.jar" "$LEGACY_JAR"
  fi
else
  echo "警告：备份中未找到后端 jar：$BACKUP_DIR/backend/java-backend-current.jar"
fi

echo "==> 恢复前端"
rm -rf "$FRONTEND_ROOT/.next" "$FRONTEND_ROOT/public" 2>/dev/null || true
mkdir -p "$FRONTEND_ROOT"

if [[ -d "$BACKUP_DIR/frontend/.next" ]]; then
  cp -a "$BACKUP_DIR/frontend/.next" "$FRONTEND_ROOT/"
fi
if [[ -d "$BACKUP_DIR/frontend/public" ]]; then
  cp -a "$BACKUP_DIR/frontend/public" "$FRONTEND_ROOT/"
fi

for f in package.json package-lock.json next.config.js; do
  if [[ -f "$BACKUP_DIR/frontend/$f" ]]; then
    cp -a "$BACKUP_DIR/frontend/$f" "$FRONTEND_ROOT/$f"
  fi
done

chown -R questionos:questionos "$FRONTEND_ROOT" 2>/dev/null || true

echo "==> 重新安装前端依赖（npm ci）"
sudo -u questionos bash -c "cd '$FRONTEND_ROOT' && npm ci --omit=dev"

echo "==> 启动服务"
systemctl restart questionos-backend
systemctl restart questionos-frontend

echo "==> 回滚完成：请访问 http://你的公网IP 并检查页面。"

