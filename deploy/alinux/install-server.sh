#!/usr/bin/env bash
# 在解压后的发布包根目录执行：sudo ./deploy/alinux/install-server.sh
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "请使用 root 执行：sudo $0"
  exit 1
fi

RELEASE_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
JAR_SRC="$RELEASE_DIR/java-backend-0.1.0.jar"
if [[ ! -f "$JAR_SRC" ]]; then
  echo "未找到 $JAR_SRC，请在解压后的发布包根目录运行本脚本。"
  exit 1
fi

USER_NAME=questionos
INSTALL_ROOT=/opt/questionos
RELEASES_ROOT="$INSTALL_ROOT/releases"
CURRENT_JAR="$INSTALL_ROOT/java-backend-current.jar"
ENV_FILE_DIR=/etc/questionos
ENV_FILE="$ENV_FILE_DIR/backend.env"
FRONTEND_SRC_DIR="$RELEASE_DIR/frontend"

RELEASE_ID="$(date +%Y%m%d-%H%M%S)"
BACKUP_ID="$RELEASE_ID"
BACKUP_DIR="$RELEASES_ROOT/$BACKUP_ID"

mkdir -p "$INSTALL_ROOT" "$RELEASES_ROOT" "$INSTALL_ROOT/frontend"
if ! id "$USER_NAME" &>/dev/null; then
  useradd --system --home-dir "$INSTALL_ROOT" --no-create-home "$USER_NAME" 2>/dev/null || \
    useradd --system -d "$INSTALL_ROOT" "$USER_NAME"
fi
chown "$USER_NAME:$USER_NAME" "$INSTALL_ROOT"

# 先停服务并备份当前版本（用于回滚）
echo "==> 停止服务并备份当前版本（回滚点：$BACKUP_ID）"
systemctl stop questionos-frontend questionos-backend 2>/dev/null || true

if [[ -f "$CURRENT_JAR" ]] || [[ -f "$INSTALL_ROOT/java-backend-0.1.0.jar" ]]; then
  mkdir -p "$BACKUP_DIR/backend"
  if [[ -f "$CURRENT_JAR" ]]; then
    cp -a "$CURRENT_JAR" "$BACKUP_DIR/backend/java-backend-current.jar"
  else
    cp -a "$INSTALL_ROOT/java-backend-0.1.0.jar" "$BACKUP_DIR/backend/java-backend-current.jar"
  fi
fi

if [[ -d "$INSTALL_ROOT/frontend" ]]; then
  mkdir -p "$BACKUP_DIR/frontend"
  # 仅备份当前部署会被覆盖的内容：.next / public / package 配置
  rm -rf "$BACKUP_DIR/frontend/.next" "$BACKUP_DIR/frontend/public" 2>/dev/null || true
  cp -a "$INSTALL_ROOT/frontend/.next" "$BACKUP_DIR/frontend/" 2>/dev/null || true
  cp -a "$INSTALL_ROOT/frontend/public" "$BACKUP_DIR/frontend/" 2>/dev/null || true
  for f in package.json package-lock.json next.config.js; do
    [[ -f "$INSTALL_ROOT/frontend/$f" ]] && cp -a "$INSTALL_ROOT/frontend/$f" "$BACKUP_DIR/frontend/$f"
  done
fi

echo "$BACKUP_ID" > "$INSTALL_ROOT/LAST_BACKUP_ID"
chown -R "$USER_NAME:$USER_NAME" "$BACKUP_DIR" 2>/dev/null || true

echo "==> 安装新 JAR"
install -o "$USER_NAME" -g "$USER_NAME" -m 0644 "$JAR_SRC" "$CURRENT_JAR"

echo "==> 安装前端文件"
rm -rf "${INSTALL_ROOT}/frontend/.next" "${INSTALL_ROOT}/frontend/public" 2>/dev/null || true
cp -R "$FRONTEND_SRC_DIR/.next" "$FRONTEND_SRC_DIR/public" "$INSTALL_ROOT/frontend/"
install -o "$USER_NAME" -g "$USER_NAME" -m 0644 \
  "$FRONTEND_SRC_DIR/package.json" \
  "$FRONTEND_SRC_DIR/package-lock.json" \
  "$FRONTEND_SRC_DIR/next.config.js" \
  "$INSTALL_ROOT/frontend/"

echo "==> npm ci (Linux 依赖，需已安装 Node 20+)"
sudo -u "$USER_NAME" bash -c "cd '$INSTALL_ROOT/frontend' && npm ci --omit=dev"

mkdir -p "$ENV_FILE_DIR"
if [[ ! -f "$ENV_FILE" ]]; then
  install -m 0600 -o root -g root "$RELEASE_DIR/deploy/alinux/backend.env.example" "$ENV_FILE"
  echo ""
  echo "!!! 已创建 $ENV_FILE ，请立即编辑填入 QUESTIONOS_AUTH_SANDBOX_TOKEN（与构建前端时一致）"
  echo ""
fi

echo "==> systemd"
install -m 0644 "$RELEASE_DIR/deploy/alinux/questionos-backend.service" /etc/systemd/system/
install -m 0644 "$RELEASE_DIR/deploy/alinux/questionos-frontend.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable questionos-backend questionos-frontend
systemctl restart questionos-backend
systemctl restart questionos-frontend

echo "==> Nginx"
install -m 0644 "$RELEASE_DIR/deploy/alinux/nginx-questionos.conf" /etc/nginx/conf.d/questionos.conf
if nginx -t 2>/dev/null; then
  systemctl reload nginx || systemctl restart nginx
else
  echo "nginx -t 失败，请检查是否与默认站点冲突（可暂时禁用 default.conf）"
  exit 1
fi

echo "==> 安装回滚脚本：/opt/questionos/rollback.sh"
install -m 0755 -o root -g root "$RELEASE_DIR/deploy/alinux/rollback.sh" "$INSTALL_ROOT/rollback.sh"

echo ""
echo "==> 完成。若刚创建 backend.env，编辑后执行："
echo "    sudo systemctl restart questionos-backend"
echo "访问: http://47.253.98.164"
