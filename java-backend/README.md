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
- 多 Agent 编排（内置主 Agent + 三方适配 Agent）
- 三方 Agent 接口：注册、能力发现、调用
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
