# QuestionOS Railway 快速部署

本方案采用两个 Railway Service：

- `questionos-backend` -> `java-backend`
- `questionos-frontend` -> `v0.2/frontend`

## 1. 准备仓库

1. 推送当前分支到 GitHub。
2. 在 Railway 新建项目并连接该 GitHub 仓库。

## 2. 创建后端服务（Java）

1. New Service -> GitHub Repo -> Root Directory 选择 `java-backend`。
2. Railway 会自动识别 `Dockerfile` 并构建。
3. 在 Variables 中配置：

```bash
QUESTIONOS_SANDBOX_TOKEN=<strong-random-token>
QUESTIONOS_ALLOWED_ORIGINS=https://<your-frontend-domain>
QUESTIONOS_LLM_ENDPOINT=https://api.openai.com
QUESTIONOS_LLM_API_KEY=<your-llm-api-key>
QUESTIONOS_LLM_MODEL=<your-model-name>
```

4. 部署成功后，记下后端公网地址（如 `https://questionos-backend.up.railway.app`）。

## 3. 创建前端服务（Next.js）

1. New Service -> GitHub Repo -> Root Directory 选择 `v0.2/frontend`。
2. Railway 自动识别 `Dockerfile` 构建。
3. 在 Variables 中配置：

```bash
NEXT_PUBLIC_API_URL=https://<your-backend-domain>
INTERNAL_API_URL=https://<your-backend-domain>
NEXT_PUBLIC_API_VERSION=1.1
NEXT_PUBLIC_SANDBOX_TOKEN=<same-as-backend-token>
```

## 4. 域名与 CORS

1. 先访问前端域名，确认页面可打开。
2. 若有自定义域名，更新后端变量 `QUESTIONOS_ALLOWED_ORIGINS`（支持逗号分隔多个 origin）。
3. 触发后端重新部署。

## 5. 冒烟测试

1. 打开前端 `consult` 页面，发起会话。
2. 能创建 session，且收到 SSE 流式回复，说明主链路正常。
3. 若报 401，检查前后端 token 是否一致。
4. 若报 CORS，检查 `QUESTIONOS_ALLOWED_ORIGINS` 是否包含当前前端 origin（协议 + 域名 + 端口）。
