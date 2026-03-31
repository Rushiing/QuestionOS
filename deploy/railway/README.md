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

## 常见问题

### `Railpack could not determine how to build the app`

说明当前 Service 的**根目录**不对，或仍在用 Railpack 猜构建方式。

1. 打开该 Service → **Settings** → **Root Directory**，后端填 `java-backend`，前端填 `v0.2/frontend`（必须和本仓库子目录一致）。
2. **Settings** → **Build** → Builder 选 **Dockerfile**（仓库里已放 `railway.json`，`build.builder` 为 `DOCKERFILE`，会覆盖为 Docker 构建）。
3. 保存后 **Redeploy**。

不要在「整个 monorepo 根目录」上部署单个应用；根目录没有单一的 `package.json` / `pom.xml` 时，Railpack 无法自动判断。

### 误把 API Key 推到了公开 GitHub

1. 在对应服务商控制台**立刻轮换/作废**该密钥（例如 Resend：重新生成 API Key，删除旧 Key）。
2. 仅通过环境变量或私有配置注入密钥，**不要**写在代码默认值里。
3. 历史提交里仍可能残留密钥；若需从公开历史中抹掉，需使用 `git filter-repo` / BFG 等重写历史（或新建仓库只推净版）。GitGuardian 文档与 Resend 控制台均有说明。
