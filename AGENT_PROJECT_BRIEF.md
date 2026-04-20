# QuestionOS — 给其他 Agent / 协作者的工程说明（最新版）

> 本文档按**当前仓库真实代码**整理，用于快速让外部 Agent 或新成员建立上下文、能改对地方。若与根目录下旧版 `v0.2/README.md`（仍写 Python/FastAPI）冲突，**以本文与 `java-backend/`、`v0.2/frontend/` 为准**。

---

## 1. 仓库与 Git

| 项 | 值 |
|----|-----|
| **GitHub（HTTPS）** | `https://github.com/Rushiing/QuestionOS.git` |
| **默认分支** | `main` |
| **克隆** | `git clone https://github.com/Rushiing/QuestionOS.git` |
| **SSH（若已配置密钥）** | `git clone git@github.com:Rushiing/QuestionOS.git` |

**推送偶发网络问题时**（如 `Connection reset`），可尝试：

```bash
git -c http.version=HTTP/1.1 push github main
```

（远程名以你本地 `git remote -v` 为准，常见为 `origin` 或 `github`。）

---

## 2. 产品是什么（一句话）

**QuestionOS**：认知协同 Agent，帮助用户在行动前**校准问题**；支持「思维校准」单线对话与 **Agora 沙盘**多角色审议两条主模式。

---

## 3. 当前技术栈（事实）

| 层级 | 技术 |
|------|------|
| **主后端** | Java 21、Spring Boot 3.3、**WebFlux**（响应式）、Maven |
| **主前端** | Next.js 14（App Router）、TypeScript、Tailwind、Zustand、Axios |
| **LLM** | OpenAI 兼容 `POST .../chat/completions`（DashScope / 自建网关等均可），由环境变量注入 |
| **持久化** | `spring.profiles.active=local`：默认 JSON 文件快照；`postgres`：JDBC + PostgreSQL（见 `application-postgres.yml`） |

仓库内可能仍存在历史目录或旧 README（Python 描述）；**当前联调主路径是 Java + v0.2 前端**。

---

## 4. 目录结构（开发时最常碰）

```
QuestionOS/
├── java-backend/                 # ★ 主 API 与编排（必读）
│   ├── pom.xml
│   ├── README.md                 # curl 联调示例
│   └── src/main/java/com/questionos/backend/
│       ├── api/                  # SandboxController、AuthController、AgentController…
│       ├── agent/                # 沙盘卡片、分诊、语义点火、多 Agent 编排
│       ├── service/              # SessionService（会话、步骤①②、SSE）
│       ├── integrations/         # OpenClawInvokeService（LLM HTTP）
│       └── domain/               # SessionMode、消息、会话模型
├── v0.2/
│   └── frontend/                 # ★ Next 咨询页、chat、history（直连或经 Next 反代 Java）
├── deploy/                       # Railway、Alinux 等部署说明
└── AGENT_PROJECT_BRIEF.md        # 本文件
```

---

## 5. 会话模式（`SessionMode`）

- **`CALIBRATION`**：思维校准多轮对话（`app/chat` 等）。
- **`SANDBOX`**：**Agora 沙盘** — 首轮含「步骤① 分诊卡片」与「步骤② 审议路由卡片」，之后多角色按轮次发言；依赖 LLM 与可选「三方 Agent」。

---

## 6. 沙盘（SANDBOX）步骤① → 步骤②（实现要点）

以下逻辑在 `SessionService.acceptUserMessage` 中，且仅在 **`sandboxDeliberationScene` 尚未写入**的首轮钉场阶段执行。

### 6.1 输入与门槛

- **议题文本**：`classifyIssueText(history)` — 按时间拼接本会话**全部用户句**（有长度上限，约 2000 字级）。
- **无意义输入**（`isMeaninglessIssue`）：只发步骤①里「无效」类卡片，`turn_done`，**不**调用分诊模型。
- **议题过简**（`!isIssueClearForStep2`）：本地信息量门槛（去口头禅后有意义字符数、英文词数等）；只发步骤①「未成形」类卡片 + 可选追问 LLM，`turn_done`，**不**调用分诊模型。

### 6.2 分诊（第一层）

- `SandboxSceneClassifier.classifyDetailed(issue)`：LLM 输出 JSON，`scene` + `confidence`（HIGH/LOW）+ `normalizedIssue`。
- `GENERAL` 或模型自陈 LOW 等情形会被压成 **LOW**（见 `SandboxSceneClassifier` 注释）。

### 6.3 语义点火（第二层，较新版本）

- 当且仅当第一层为 **`LOW`** 时，调用 `MainCalibrateAgent.isSandboxSemanticIgnitionReady(issue, classificationSnapshot)`。
- 另一路 LLM 只输出 `{"ready":true|false}`：判断用户累积信息是否**已足以启动沙盘**（明确：步骤②内仍会**继续追问**，不要求议题完美）。
- **`ready == true`**：`SandboxClassificationResult.withSemanticIgnitionHigh()` → 视为 **HIGH**，写入 `sandboxDeliberationScene`，**进入步骤②**（与原生 HIGH 同路径）；`semanticIgnitionOverride=true`。
- 调用/解析失败：保守 **`false`**，行为等同旧版 LOW（追问、不入室）。

### 6.4 步骤②与一次性路由

- **步骤②**：`sandbox-route` 消息 + SSE `sandbox_route`；同一会话通过 `sessionAlreadyHasSandboxRoute` **只发一次**。
- **步骤①卡片 Markdown**：`SandboxClassifyCard` — 四块：本轮追问、追问理由、分诊信心（自然语言短句）、进入审议室；追问内容由 `MainCalibrateAgent.generateSandboxStep1ClarifyFollowup` 的 **lite** 片段提供。

### 6.5 SSE `sandbox_classify` payload（与前端相关字段示例）

除 `content`、`scene`、`confidence`、`requiresClarification` 等外，较新字段包括：

- **`semanticIgnitionOverride`**：`boolean`，语义点火将 LOW 升为 HIGH 时为 `true`。
- **`step1ClarifyGenerated` / `step1ClarifyChars`**：追问 LLM 是否成功及长度。

前端消费见 `v0.2/frontend/app/consult/page.tsx`（`sandbox_classify` / `sandbox_route` 分支）。

---

## 7. HTTP API（沙箱会话）

Base path：`/api/v1/sandbox/sessions`（定义于 `SandboxController`）。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/sandbox/sessions` | 创建会话，`body: { "mode": "SANDBOX" \| "CALIBRATION", "question": "..." }` |
| GET | `/api/v1/sandbox/sessions` | 会话列表 |
| POST | `/api/v1/sandbox/sessions/{id}/messages` | 发送用户消息；建议带 `Idempotency-Key` |
| GET | `/api/v1/sandbox/sessions/{id}/stream` | **SSE**；支持 `Last-Event-ID` 续传 |
| GET | `/api/v1/sandbox/sessions/{id}/messages` | 历史消息列表 |

**鉴权**：`Authorization: Bearer <token>`；本地默认 token 与配置项 `questionos.auth.sandbox-token` 一致，常为 `sk-sandbox-dev`（勿与第三方 Agent 的 API Key 混用）。

**重要实现细节**：`POST .../messages` 在 **boundedElastic** 上执行 `SessionService.acceptUserMessage`，因其内部对 LLM 使用阻塞调用；**不可**在 reactor-http 事件线程上直接 `block`（已在 Controller 处理）。

更完整 curl 示例见 `java-backend/README.md`。

---

## 8. 环境变量（后端核心）

以下前缀在 `java-backend/src/main/resources/application.yml` 中映射为 `questionos.*` 属性。

| 环境变量 | 作用 |
|----------|------|
| `QUESTIONOS_LLM_ENDPOINT` | OpenAI 兼容 chat 根 URL（可含或不含 `/v1/chat/completions`，由集成层归一化） |
| `QUESTIONOS_LLM_API_KEY` | API Key |
| `QUESTIONOS_LLM_MODEL` | 模型名 |
| `QUESTIONOS_LLM_TIMEOUT_SECONDS` | 超时（默认较大，适配长推理） |
| `QUESTIONOS_SANDBOX_TOKEN` | 沙箱/开发 Bearer token（默认 `sk-sandbox-dev`） |
| `QUESTIONOS_ALLOWED_ORIGINS` | CORS，逗号分隔 |
| `SPRING_PROFILES_ACTIVE` | `local`（默认文件快照）或 `postgres` 等 |
| `PORT` | 容器内监听端口（默认 8080） |

生产务必设置强随机 `QUESTIONOS_AUTH_JWT_SECRET` 等（见 `application.yml` 注释）。

---

## 9. 本地运行（最短路径）

### 9.1 Java 后端

```bash
cd java-backend
mvn -DskipTests compile
mvn spring-boot:run
```

默认监听 `http://0.0.0.0:8080`。需配置 `QUESTIONOS_LLM_*` 后，内置 Agent / 沙盘 / 语义点火 等才会真实调用模型。

### 9.2 Next 前端（v0.2）

```bash
cd v0.2/frontend
npm install
npm run dev
```

- API 基址：`NEXT_PUBLIC_API_URL` 或 `INTERNAL_API_URL`（服务端代理用），默认开发指向 `http://127.0.0.1:8080`（见 `lib/api.ts`、`app/api/[[...path]]/route.ts`）。
- 浏览器端兜底：`lib/runtime-config.ts` 中 `SANDBOX_FALLBACK_TOKEN` 可与后端 sandbox token 对齐。

---

## 10. 观测与运维

- 健康：`GET /actuator/health`
- 指标：`GET /actuator/prometheus`
- 部署参考：`deploy/railway/README.md`、`deploy/alinux/README.md`

---

## 11. 给其他 Agent 的修改建议

1. **先读再改**：沙盘流程以 `SessionService` 为枢纽；分诊 `SandboxSceneClassifier`；步骤①文案 `SandboxClassifyCard`；追问与语义点火 `MainCalibrateAgent`；勿只改前端字符串而后端事件字段不一致。
2. **WebFlux + block**：凡在 Service 里新增 `Mono.block` / 同步 HTTP，须保证从 **boundedElastic**（或显式 scheduler）调用，避免阻塞 Netty IO 线程。
3. **幂等与 SSE**：消息提交依赖 `Idempotency-Key`；流式端注意 `Last-Event-ID` 与事件 envelope 结构（`StreamEvent` + JSON）。
4. **文档漂移**：修改 API 或 SSE 契约时，同步更新 `java-backend/README.md` 或本文件；**不要**假设 `v0.2/README.md` 的 Python 结构仍存在。
5. **编译**：`mvn -DskipTests compile` 为 Java 侧最低自检。

---

## 12. 版本锚点

本文档撰写时，`main` 上已包含：**沙盘步骤①四段卡片**、**分诊信心自然语言**、**LOW 时语义点火 `semanticIgnitionOverride` 与步骤②放行** 等能力。具体以：

```bash
git log -1 --oneline
```

为准。

---

## 13. 许可与联系

License 以仓库根目录 `LICENSE` 或各子项目声明为准（若存在）。Issue / PR 使用上述 GitHub 仓库页面即可。
