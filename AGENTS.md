# QuestionOS · 项目级指令

> 2026-07-06 从 `CLAUDE.md` 迁移为 `AGENTS.md`。本文件是 Codex 主入口；旧 `CLAUDE.md` 仅保留历史兼容，不再作为新增规则入口。

## 项目身份

**QuestionOS**：认知协同 Agent，帮助用户在行动前**校准问题**。两条主模式：
- `CALIBRATION` — 思维校准多轮对话（`app/chat`）
- `SANDBOX` — Agora 沙盘多角色审议（首轮含步骤①分诊卡片 + 步骤②审议路由卡片）

**GitHub**：`Rushiing/QuestionOS`
**部署**：Railway（`questionos-app.up.railway.app/`）
**版本锚点**：不要在长期文档写死 commit；用 `git log -1 --oneline` 与 Railway deployment metadata 核对。

## 技术栈

| 层级 | 技术 |
|------|------|
| 主后端 | Java 21、Spring Boot 3.3、WebFlux（响应式）、Maven |
| 主前端 | Next.js 14（App Router）、TypeScript、Tailwind、Zustand、Axios |
| LLM | OpenAI 兼容 `POST .../chat/completions`（环境变量注入） |
| 存储 | 本地：JSON 快照（`spring.profiles.active=local`）；生产：PostgreSQL + Flyway |

**关键约束**：当前主要联调路径是 **Java + v0.2 前端**。仓库里仍有旧 Python/FastAPI 描述（`v0.2/README.md`），忽略之。

## 关键代码位置

- 核枢纽：`java-backend/src/main/java/com/questionos/backend/service/SessionService.java`
- 分诊逻辑：`SandboxSceneClassifier`
- 沙盘追问与语义点火：`MainCalibrateAgent`
- 步骤①UI：`SandboxClassifyCard`
- 前端消费：`v0.2/frontend/app/consult/page.tsx`（响应 SSE `sandbox_classify` / `sandbox_route`）

## 本地运行

### Java 后端
```bash
cd java-backend
mvn -DskipTests compile    # 编译检查
mvn spring-boot:run        # 启动，监听 8080
```
**前置条件**：需设置 `QUESTIONOS_LLM_*` 环境变量后，Agent / 沙盘才会真实调用模型。

### Next.js 前端
```bash
cd v0.2/frontend
npm install
npm run dev                # http://127.0.0.1:3000
```
- API 代理：`app/api/[[...path]]/route.ts` 反代到后端（开发指向 `http://127.0.0.1:8080`）
- `lib/api.ts` / `lib/runtime-config.ts` 中 `SANDBOX_FALLBACK_TOKEN` 与后端 sandbox token 对齐

## Railway 部署

### 后端服务（Java）
- 连接 GitHub `Rushiing/QuestionOS`
- **Root Directory 留空**（不要填 `java-backend`）
- Config as Code：`/railway.backend.json`
- 关键 Variables：
  ```
  RAILWAY_DOCKERFILE_PATH=Dockerfile.railway-backend
  QUESTIONOS_SANDBOX_TOKEN=<strong-random-token>
  QUESTIONOS_ALLOWED_ORIGINS=https://<your-frontend-domain>
  QUESTIONOS_LLM_ENDPOINT=https://api.openai.com
  QUESTIONOS_LLM_API_KEY=<your-llm-api-key>
  QUESTIONOS_LLM_MODEL=<your-model-name>
  ```
- Healthcheck：`/actuator/health/liveness`，超时 300s（Java 冷启动）
- 思维校准追问易超时，默认 240s，需要可改 300～420（`QUESTIONOS_LLM_TIMEOUT_SECONDS`）

### 前端服务（Next.js）
- 同仓库 service，Root Directory：`/v0.2/frontend`
- Config as Code：`/v0.2/frontend/railway.json`
- 关键 Variables：
  ```
  INTERNAL_API_URL=http://questionos.railway.internal:8080
  # NEXT_PUBLIC_API_URL 生产留空/不设；浏览器走同源 /api
  NEXT_PUBLIC_API_VERSION=1.1
  NEXT_PUBLIC_SANDBOX_TOKEN=<same-as-backend-token>
  ```
- Generate Domain 后回到后端更新 `QUESTIONOS_ALLOWED_ORIGINS` 并重新部署后端

## 工作流

1. **Read → Edit → Bash**（编译 / 测试）—— 优先修改现有文件，避免无谓新建
2. **代码改动后立即本地验证**（`mvn compile` / `npm run build`）
3. **git 提交**：先 `git status` + `git diff`；commit message 英文为主
4. **AGENT_PROJECT_BRIEF.md / java-backend/README.md** 与代码改动同步更新

## 改动检查清单

| 改了什么 | 同步什么 |
|---|---|
| API 路由 / SSE 事件 | `AGENT_PROJECT_BRIEF.md` 或 `java-backend/README.md` |
| 环境变量 / 配置 | `application.yml` 映射 + Railway 部署文档 |
| 沙盘流程（`SessionService` / `SandboxSceneClassifier`） | AGENT_PROJECT_BRIEF 的「给其他 Agent 的修改建议」 |
| 前后端数据结构 | Java SSE payload 与前端消费同步（不只改字符串） |

## 技术细节

- **WebFlux + 阻塞调用**：所有新增的 `Mono.block()` 或同步 HTTP 必须从 `boundedElastic` 线程池跑——绝不能在 Netty IO 线程上 `block`（Controller 已处理，见 `SessionService.acceptUserMessage`）
- **幂等**：消息提交靠 `Idempotency-Key`
- **SSE 续传**：支持 `Last-Event-ID`
- API 文档：`java-backend/README.md`（Base path：`/api/v1/sandbox/sessions`）

## 常见问题

- **502**：检查 healthcheck 路径与超时；确认 `server.address=0.0.0.0`；查看 OOM（`Killed`）
- **CORS 错**：检查 `QUESTIONOS_ALLOWED_ORIGINS` 是否与浏览器 origin 完全一致（含协议 + 域名 + 端口）
- **Railpack 误判**：确保 Root Directory 留空 + Variables 设置 `RAILWAY_DOCKERFILE_PATH`

## Codex 补充规范（2026-07-06）

本节只补充当前项目级指令。后续如果规则变化，追加日期说明，不要直接覆盖历史判断。

工作前置：

- 开始前先跑 `git status -sb`，确认是否有用户或其他 AI 留下的改动。
- 先判断任务属于 Java 后端、Next 前端、SSE 协议、沙盘流程、部署配置、LLM 调用或文档同步中的哪一类。
- 涉及 API 路由、SSE 事件、环境变量、沙盘流程或前后端数据结构时，先检查上面的“改动检查清单”。
- 仓库内仍有旧 Python/FastAPI 描述时，以本文件的 Java + v0.2 前端路径为准。

改动边界：

- 不要在 Netty IO 线程上新增阻塞调用；新增同步/阻塞逻辑必须放到 `boundedElastic`。
- 不要只改前端或只改后端数据结构；SSE payload 与消费端必须同步。
- 不要把 Railway Root Directory 改成子目录；当前部署约束是 Root Directory 留空 + `RAILWAY_DOCKERFILE_PATH`。

验证与交付：

- Java 后端改动至少跑 `mvn -DskipTests compile`；测试相关改动再跑对应测试。
- 前端改动至少跑 `npm run build` 或说明为什么不能跑。
- 完成时说明改了哪个层级、同步了哪些文档、验证命令是什么。

## 规范化交付（2026-07-15）

- 交付流程以 `docs/DELIVERY_WORKFLOW.md` 为准。
- 禁止直接在 `main` 上开发或提交；工作区不干净、中高风险或并行任务使用独立 worktree。
- PR 必须标明 `low` / `medium` / `high` 风险、验证证据、部署范围和回滚方式。
- 基础 CI 不调用真实 OAuth、真实 LLM 或生产数据。
- 合并 `main`、修改 GitHub 门禁、修改 Railway 配置 / Variables、生产数据操作和删除资源必须由用户确认。
- Railway 验收必须分别检查 frontend、backend、Postgres 和 smoke-monitor，再走真实用户路径。
- Railway 当前配置、watch paths、release evidence 与回滚以 `deploy/railway/README.md` 为准。
- Prompt/Mock/Seed/Summary 的支持边界与命令以 `docs/PROMPT_QUALITY_WORKFLOW.md` 为准；真实 LLM eval 不进入 required CI。
