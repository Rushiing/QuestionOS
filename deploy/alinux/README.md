# QuestionOS：Alibaba Cloud Linux 3 + 轻量应用服务器（本机进程 + Nginx:80）

面向：**本机打好包上传**、**仅开放 80**（443 以后再加）、公网入口示例 **`http://47.253.98.164`**。

**零基础请直接看：[STEP-BY-STEP.md](./STEP-BY-STEP.md)**（分步说明更细，含检查清单与排错提示）。

## 一、在你自己的电脑上打包

需已安装 **JDK 21**、**Maven**、**Node.js 20+**。

在项目根目录执行（把 `YOUR_TOKEN` 换成生产用 sandbox token，与前端一致）：

```bash
chmod +x deploy/alinux/pack-release.sh
export PUBLIC_API_URL=http://47.253.98.164
export SANDBOX_TOKEN='YOUR_TOKEN'
./deploy/alinux/pack-release.sh
```

生成 `dist/questionos-release-*.tar.gz`。

构建说明：

- `NEXT_PUBLIC_API_URL`：浏览器与 SSE 访问后端的地址（当前为公网 IP）。
- `INTERNAL_API_URL=http://127.0.0.1:8080`：Next 服务端 rewrite 直连本机 Java，不绕公网。
- `NEXT_PUBLIC_SANDBOX_TOKEN`：须与后端 `QUESTIONOS_AUTH_SANDBOX_TOKEN` 一致。

## 二、上传到服务器

```bash
scp dist/questionos-release-*.tar.gz root@47.253.98.164:/tmp/
```

## 三、在服务器上安装依赖（仅首次）

SSH 登录后（**Alibaba Cloud Linux 3**）：

```bash
sudo dnf install -y nginx java-21-openjdk-headless git
curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
sudo dnf install -y nodejs
sudo systemctl enable --now nginx
```

若 `nginx -t` 报 **80 端口冲突** 或与默认页冲突，可先禁用自带默认站点再执行安装脚本：

```bash
sudo mv /etc/nginx/conf.d/default.conf /etc/nginx/conf.d/default.conf.bak 2>/dev/null || true
sudo systemctl reload nginx
```

防火墙（轻量控制台「防火墙」或本机 `firewalld`）放行 **TCP 80**。

## 四、解压并安装服务

压缩包**根目录**下直接是 `java-backend-0.1.0.jar`、`frontend/`、`deploy/`，没有外层文件夹。

```bash
cd /tmp
tar xzf questionos-release-*.tar.gz
sudo chmod +x deploy/alinux/install-server.sh
sudo ./deploy/alinux/install-server.sh
```

脚本会：

- 创建用户 `questionos`，目录 `/opt/questionos`
- 安装 JAR、`frontend` 并在该目录执行 `npm ci --omit=dev`
- 写入 `/etc/questionos/backend.env`（**若不存在**；请立刻编辑填入 token）
- 安装 Nginx 片段与两条 systemd 服务并启动

**必须手动编辑**（首次）：

```bash
sudo nano /etc/questionos/backend.env
```

内容示例：

```env
QUESTIONOS_AUTH_SANDBOX_TOKEN=你的生产token
QUESTIONOS_CORS_ALLOWED_ORIGINS=http://47.253.98.164,http://localhost:3000
```

保存后：

```bash
sudo systemctl restart questionos-backend questionos-frontend nginx
```

## 五、验证

- 浏览器打开：`http://47.253.98.164`（沙盘 / 咨询页应能加载）
- 后端健康（需带鉴权头，将 `TOKEN` 换成你的 token）：

```bash
curl -s -H "Authorization: Bearer TOKEN" http://127.0.0.1:8080/actuator/health
```

## 七、回滚（支持历史版本）

每次你在服务器上执行 `install-server.sh` 时，脚本都会把“当前正在运行的版本”备份到：

- `/opt/questionos/releases/<时间戳>/`

并写入一个记录文件：

- `/opt/questionos/LAST_BACKUP_ID`

若你新部署后发现线上问题，可以一键回滚到刚被备份的那版（即上一版）：

```bash
sudo /opt/questionos/rollback.sh
```

如果你想回滚到更早的某次备份（指定目录名），查看可用备份：

```bash
ls -1 /opt/questionos/releases
```

然后指定：

```bash
sudo /opt/questionos/rollback.sh 20250325-153012
```

## 六、以后仅更新版本

1. 本机重新执行 `pack-release.sh` 生成新包。  
2. 上传解压到临时目录，将 `java-backend-0.1.0.jar` 与 `frontend/` 覆盖到 `/opt/questionos/`，前端目录执行：

   ```bash
   sudo -u questionos bash -c 'cd /opt/questionos/frontend && npm ci --omit=dev'
   ```

3. `sudo systemctl restart questionos-backend questionos-frontend`

## 八、上 HTTPS（以后）

在轻量或 CDN 上绑定域名与证书后，把 `NEXT_PUBLIC_API_URL` 改为 `https://你的域名` 重新构建前端，并更新 `QUESTIONOS_CORS_ALLOWED_ORIGINS`；Nginx 增加 `listen 443 ssl` 即可（本包未内置证书步骤）。

## 文件说明

| 文件 | 用途 |
|------|------|
| `pack-release.sh` | 开发机：Maven 打 JAR + Next build + 打 tar 包 |
| `install-server.sh` | 服务器：安装目录、systemd、nginx |
| `nginx-questionos.conf` | `location /api/` 反代 Java（含 SSE 相关头） |
| `questionos-backend.service` | `java -jar`，读 `/etc/questionos/backend.env` |
| `questionos-frontend.service` | `next start` 仅监听 `127.0.0.1:3000` |
| `backend.env.example` | 环境变量模板 |
