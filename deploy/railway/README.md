# QuestionOS Railway 快速部署

本方案采用两个 Railway Service（推荐用仓库根的 `Dockerfile.railway-*`，避免 Railpack 误判）：

- 后端：`Dockerfile.railway-backend`
- 前端：`Dockerfile.railway-frontend`

## 1. 准备仓库

1. 推送当前分支到 GitHub。
2. 在 Railway 新建项目并连接该 GitHub 仓库。

## 2. 创建后端服务（Java）

### 推荐：仓库根 + 强制 Dockerfile（避免 Railpack / start.sh）

Railway 在 monorepo 里**不会**自动读子目录里的 `railway.json`（[官方说明](https://docs.railway.com/deployments/monorepo)：Config 文件路径要相对仓库根写绝对路径，否则容易退回 Railpack，去整仓找 `start.sh`）。

1. 新建 Service，连接 **`Rushiing/QuestionOS`**。
2. **Root Directory 留空**（不要填 `java-backend`，表示用整个仓库当构建上下文）。
3. 打开该 Service → **Variables**，新增（名称区分大小写）：

```bash
RAILWAY_DOCKERFILE_PATH=Dockerfile.railway-backend
```

4. 保存后会用仓库根目录的 `Dockerfile.railway-backend` 构建（日志里应出现 `Using Dockerfile` 一类提示，而不是 Railpack）。

5. 继续在 **Variables** 里配置运行所需变量：

```bash
QUESTIONOS_SANDBOX_TOKEN=<strong-random-token>
QUESTIONOS_ALLOWED_ORIGINS=https://<your-frontend-domain>
QUESTIONOS_LLM_ENDPOINT=https://api.openai.com
QUESTIONOS_LLM_API_KEY=<your-llm-api-key>
QUESTIONOS_LLM_MODEL=<your-model-name>
```

### 备选：只检出子目录（若你已在 UI 里配好 Config 路径）

1. Root Directory 填 **`/java-backend`**（官方示例带前导 `/`）。
2. 若仍走 Railpack：在 Service 设置里把 **Config as code** 指向 **`/java-backend/railway.json`**（路径相对仓库根）。

6. **Networking** → **Generate Domain**，记下后端公网地址。

## 3. 创建前端服务（Next.js）

1. 再建一个 Service，连接同一仓库 **`Rushiing/QuestionOS`**。
2. **Root Directory 留空**。
3. **Variables** 里先加：

```bash
RAILWAY_DOCKERFILE_PATH=Dockerfile.railway-frontend
```

4. 再加前端运行变量（**运行时**生效即可；代理在 `app/api/[[...path]]` 里读 `INTERNAL_API_URL`，不必为 rewrite 在构建期注入）：

```bash
NEXT_PUBLIC_API_URL=https://<your-backend-domain>
INTERNAL_API_URL=https://<your-backend-domain>
NEXT_PUBLIC_API_VERSION=1.1
NEXT_PUBLIC_SANDBOX_TOKEN=<same-as-backend-token>
```

两处后端地址建议一致（公网 `https://…up.railway.app` 或同项目 **Private Networking** 的 `http://<后端服务名>.railway.internal:<端口>`，以前端容器能 `fetch` 通为准）。

5. **Generate Domain**，得到前端地址；把后端 `QUESTIONOS_ALLOWED_ORIGINS` 改成该前端 origin（含 `https://`），**Redeploy** 后端。

## 4. 域名与 CORS

1. 先访问前端域名，确认页面可打开。
2. 若有自定义域名，更新后端变量 `QUESTIONOS_ALLOWED_ORIGINS`（支持逗号分隔多个 origin）。
3. 触发后端重新部署。

## 5. 冒烟测试

1. 打开前端 `consult` 页面，发起会话。
2. 能创建 session，且收到 SSE 流式回复，说明主链路正常。
3. 若报 401，检查前后端 token 是否一致。
4. 若报 CORS，检查 `QUESTIONOS_ALLOWED_ORIGINS` 是否包含当前前端 origin（协议 + 域名 + 端口）。

## 常见问题

### `Railpack could not determine how to build the app` / `start.sh not found`

多为 **没用 Docker 构建**、Railpack 在整仓里乱猜（还会瞄到 `v0.2/start.sh`）。

1. **Root Directory 留空**。
2. **Variables** 设置 `RAILWAY_DOCKERFILE_PATH`：后端 `Dockerfile.railway-backend`，前端 `Dockerfile.railway-frontend`。
3. **Redeploy**，构建日志里应出现使用 Dockerfile 的提示。

备选：Root Directory 用 **`/java-backend`** 或 **`/v0.2/frontend`**（带前导 `/`），并在设置里把 **Config as code** 指到 **`/java-backend/railway.json`** 或 **`/v0.2/frontend/railway.json`**（[monorepo 说明](https://docs.railway.com/deployments/monorepo)）。

### 误把 API Key 推到了公开 GitHub

1. 在对应服务商控制台**立刻轮换/作废**该密钥（例如 Resend：重新生成 API Key，删除旧 Key）。
2. 仅通过环境变量或私有配置注入密钥，**不要**写在代码默认值里。
3. 历史提交里仍可能残留密钥；若需从公开历史中抹掉，需使用 `git filter-repo` / BFG 等重写历史（或新建仓库只推净版）。GitGuardian 文档与 Resend 控制台均有说明。
