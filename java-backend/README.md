# QuestionOS Java Backend (WebFlux)

## 启动

```bash
cd java-backend
mvn spring-boot:run
```

默认端口：`8080`

默认测试 token：

```text
Authorization: Bearer sk-sandbox-dev
```

## 关键能力

- 多 session / 多轮对话
- SSE 流式输出，支持 `Last-Event-ID` 续传
- `Idempotency-Key` 幂等消息提交
- 多 Agent 编排（思维校准主 Agent + 沙盘内置四席）
- 沙盘首轮分诊、审议路由与内置多角色轮转
- 基础治理：鉴权、限流、请求链路 ID、审计、Prometheus 指标

## 快速联调

1) 创建会话

```bash
curl -X POST "http://localhost:8080/api/v1/sandbox/sessions" \
  -H "Authorization: Bearer sk-sandbox-dev" \
  -H "Content-Type: application/json" \
  -d '{"mode":"SANDBOX","question":"我想转 AI，但不知道从哪里开始"}'
```

2) 建立 SSE

```bash
curl -N "http://localhost:8080/api/v1/sandbox/sessions/<sessionId>/stream" \
  -H "Authorization: Bearer sk-sandbox-dev"
```

3) 发送消息

```bash
curl -X POST "http://localhost:8080/api/v1/sandbox/sessions/<sessionId>/messages" \
  -H "Authorization: Bearer sk-sandbox-dev" \
  -H "Idempotency-Key: idem-001" \
  -H "Content-Type: application/json" \
  -d '{"content":"我最大的困难是容易焦虑"}'
```

## 观测接口

- 健康检查：`/actuator/health`
- 指标：`/actuator/prometheus`

## 邮箱认领（本地实现，未发布）

Google-only 旧账号可在 `/recover` 通过原邮箱一次性口令设置密码，沿用原 user_id 与历史。同邮箱禁止新建账号。新增 `/api/auth/email-claim/request` 与 `/confirm`，V7 邮箱唯一索引与认领表。上线前需配置后端 `QUESTIONOS_SMTP_HOST/PORT/USERNAME/PASSWORD`、`QUESTIONOS_CLAIM_MAIL_FROM`（STARTTLS/587），验收真实投递。发信服务尚未开通；未修改 Railway Variables 或生产数据库。完整行为与回退限制见 `deploy/aliyun/login-continuity.md`（仓库根目录）。

认证数据库测试：准备专用本地 `qos_auth_test` 空库后执行 `QOS_AUTH_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:端口/qos_auth_test mvn test`。可用 `QOS_AUTH_TEST_DB_USER` 指定用户名。测试只接受此 loopback 专用数据库，创建随机 schema 并在结束时删除；不得指向生产或真实备份。未设置变量时数据库集成测试跳过；CI 已显式启用。

### 沙盘事实校对（迁移候选）

四席/中场草稿发布前增加 `sandbox:evidence-review`，仅使用用户原话核对草稿。百炼 DeepSeek V4 沙盘生成及校对请求启用 `enable_thinking=true`、`thinking_budget=4096`；校准、分诊、标题等其余调用仍遵循现有配置。模型输出上限仍沿用应用配置。每回合多一次请求和等待，校对失败不发布草稿。此分支仅在隔离演练环境验收，尚未切换生产。供应商参数依据：https://www.alibabacloud.com/help/zh/model-studio/deepseek-api 。

### 2026-10-09 角色来源约束

三位沙盘分析角色使用`sandbox:grounded-role`，最终报告使用`sandbox:grounded-report`请求JSON object；`GroundedRoleReply`与`GroundedReport`验证用户source_id以及条件判断中的完整数量/上下限，再渲染Markdown；外部SSE仍为agent_start/agent_chunk/agent_done。新精确数量不直接发布；校验失败最多重写一次。二次输出仅有数量问题时省略不可靠推演并明确提示，保留可读依据与建议；格式/来源错误仍发agent_error。中场小结仍走原有生成和校对。建议与追问不作为既有事实进行数量拦截；建议内容仍需人工判断。用户引用由程序原样输出，但这不证明用户信息客观正确，也不验证所有推理语义。百炼JSON模式依据：[官方结构化输出文档](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output)。测试与真实验收见`../deploy/aliyun/quality-followup-20261009.md`。

#### 正式域名 CORS（2026-10-09）

`QUESTIONOS_ALLOWED_ORIGINS` 的逗号分隔精确来源由 CorsOriginEvaluator 同时用于响应过滤器及 WebFlux `/api/**` 映射。新增域名后必须验收携带实际 `Origin` 的创建会话和发消息请求；仅无Origin的HTTP smoke不能证明浏览器写入正常。回归测试：SessionCorsTest。
