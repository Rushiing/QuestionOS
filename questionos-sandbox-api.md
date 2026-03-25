# QuestionOS 沙盘推演 Agent 接入协议

**版本**: v1.1  
**日期**: 2026-03-24  
**设计目标**: 稳定多 session、多轮、多 agent 对话，并支持三方 Agent 快速接入

---

## 架构选型

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **HTTP + SSE** | 浏览器原生支持、单向推送足够、自动重连 | 仅服务端→客户端单向 |⭐⭐⭐⭐ |
| WebSocket | 双向实时、成熟生态 | 实现复杂、需要心跳保活 |⭐⭐ |
| 轮询 HTTP | 最简单 | 延迟高、浪费资源 |⭐ |

**最终选择**: HTTP + SSE（Server-Sent Events）

---

## 认证方式

### 方案 A：Bearer Token（推荐）
```http
Authorization: Bearer sk-sandbox-xxx
```

### 方案 B：短时 stream_ticket（仅浏览器 EventSource）
1. 客户端先通过受保护接口换取短时票据（有效期建议 <= 60 秒）  
2. 再用 `GET /stream?stream_ticket=...` 建立 SSE  
3. 不建议长期使用 query token

---

## 基础约定

- **Base Path**: `/api/v1`
- **版本协商**: Header `X-API-Version: 1.1`
- **幂等头**: `Idempotency-Key`（建议在 `POST /messages` 必传）
- **链路头**: `X-Request-Id`（可选；服务端会回写）
- **SSE 续传**: 使用 `Last-Event-ID`

---

## 接口定义

### 1. 创建会话

```http
POST /api/v1/sandbox/sessions
Content-Type: application/json
Authorization: Bearer <token>
X-API-Version: 1.1

{
  "mode": "sandbox",
  "question": "想转行做AI，但技术基础薄弱怎么办？"
}
```

**响应**:
```json
{
  "sessionId": "sess_abc123xyz",
  "status": "created",
  "createdAt": "2026-03-24T06:00:00Z"
}
```

**参数说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| mode | string | 是 | `sandbox`（沙盘推演）或 `calibration`（思维校准） |
| question | string | 是 | 用户初始问题 |

---

### 2. 发送用户消息

```http
POST /api/v1/sandbox/sessions/{sessionId}/messages
Content-Type: application/json
Authorization: Bearer <token>
Idempotency-Key: idem_20260324_001

{
  "content": "我觉得自己学太慢了"
}
```

**响应**:
```json
{
  "messageId": "msg_001",
  "status": "accepted",
  "idempotencyKey": "idem_20260324_001"
}
```

---

### 3. 接收 Agent 回复（SSE 流）

```http
GET /api/v1/sandbox/sessions/{sessionId}/stream
Accept: text/event-stream
Authorization: Bearer <token>
Last-Event-ID: 125
```

**响应头**:
```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

**事件格式**:

```sse
event: agent_chunk
data: {"eventId":"evt_1","seq":126,"turnId":2,"payload":{"content":"让我们先"}}
id: 1

event: handoff
data: {"eventId":"evt_2","seq":127,"turnId":2,"payload":{"content":"main->third-party"}}
id: 2

event: done
data: {"eventId":"evt_3","seq":128,"turnId":2,"payload":{"content":"本轮结束"}}
id: 3

event: heartbeat
data: {"type":"heartbeat"}
id: hb-10
```

**事件类型**:
| 事件 | data 结构 | 说明 |
|------|-----------|------|
| `agent_start` | `{"payload":{"content":"..."}}` | Agent 开始处理 |
| `agent_chunk` | `{"payload":{"content":"..."}}` | 流式输出片段 |
| `handoff` | `{"payload":{"content":"main->third-party"}}` | Agent 切换 |
| `agent_done` | `{"payload":{"content":"..."}}` | 子 Agent 结束 |
| `done` | `{"payload":{"content":"本轮结束"}}` | 本轮回复结束 |
| `error` | `{"type":"error","code":"xxx","message":"..."}` | 错误事件 |
| `heartbeat` | `{"type":"heartbeat"}` | 保活心跳（15~30秒） |

---

### 4. 查询会话状态

```http
GET /api/v1/sandbox/sessions/{sessionId}
Authorization: Bearer <token>
```

**响应**:
```json
{
  "sessionId": "sess_abc123xyz",
  "mode": "sandbox",
  "status": "active",
  "messageCount": 5,
  "createdAt": "2026-03-24T06:00:00Z",
  "expiresAt": "2026-03-24T07:00:00Z",
  "lastActivityAt": "2026-03-24T06:15:00Z"
}
```

**状态枚举**:
| 状态 | 说明 |
|------|------|
| `created` | 刚创建，等待首条消息 |
| `active` | 活跃会话中 |
| `completed` | 用户主动结束 |
| `expired` | 超时自动关闭（默认 1 小时无活动） |

---

### 5. 删除会话

```http
DELETE /api/v1/sandbox/sessions/{sessionId}
Authorization: Bearer <token>
```

**响应**:
```json
{
  "status": "deleted"
}
```

---

## 三方 Agent 接入接口（v1）

### 6. 注册 Agent
```http
POST /api/v1/agents/register
Authorization: Bearer <token>
Content-Type: application/json

{
  "agentId": "partner-001",
  "provider": "OpenClaw",
  "endpoint": "https://partner.example.com/invoke",
  "scope": "sandbox:invoke"
}
```

### 7. 能力发现
```http
GET /api/v1/agents/capabilities
Authorization: Bearer <token>
```

### 8. 调用 Agent
```http
POST /api/v1/agents/{agentId}/invoke
Authorization: Bearer <token>
Content-Type: application/json
```

---

## 错误码定义

| HTTP 状态码 | code | 说明 |
|-------------|------|------|
| 400 | `INVALID_REQUEST` | 请求参数错误 |
| 401 | `UNAUTHORIZED` | Token 无效或过期 |
| 404 | `SESSION_NOT_FOUND` | 会话不存在 |
| 409 | `SESSION_EXPIRED` | 会话已过期 |
| 429 | `RATE_LIMITED` | 请求频率超限 |
| 500 | `INTERNAL_ERROR` | 服务端错误 |

---

## 顺序与可靠性语义

1. 每个 SSE 事件包含 `seq`，全局单调递增。  
2. 客户端重连时应携带 `Last-Event-ID`，服务端从下一条开始回放。  
3. `Idempotency-Key` 在 `POST /messages` 上至少缓存 24 小时，重复请求返回同一 `messageId`。  
4. 服务端每 15~30 秒发送 `heartbeat`，避免代理层断链。  
5. 推荐客户端维护 `lastSeq`，检测缺口后主动重连。  

**错误响应格式**:
```json
{
  "error": {
    "code": "SESSION_NOT_FOUND",
    "message": "会话 sess_xxx 不存在或已过期",
    "requestId": "req_abc123"
  }
}
```

---

## 前端接入示例

```javascript
// 1. 创建会话
const res = await fetch('/api/sandbox/sessions', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
  body: JSON.stringify({ mode: 'sandbox', question: '...' })
});
const { sessionId } = await res.json();

// 2. 建立 SSE 连接
const eventSource = new EventSource(`/api/v1/sandbox/sessions/${sessionId}/stream?stream_ticket=${ticket}`);

eventSource.addEventListener('agent_chunk', (e) => {
  const { payload } = JSON.parse(e.data);
  const { content } = payload;
  appendToChat(content);
});

eventSource.addEventListener('done', (e) => {
  console.log('本轮回复完成');
});

eventSource.addEventListener('error', (e) => {
  console.error('SSE 错误', e);
  eventSource.close();
});

// 3. 发送消息
await fetch(`/api/v1/sandbox/sessions/${sessionId}/messages`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
    'Idempotency-Key': `idem-${Date.now()}`
  },
  body: JSON.stringify({ content: '...' })
});
```

---

## Java WebFlux 实现建议

1. 使用 `Flux<ServerSentEvent<?>>` 推送事件。  
2. 通过 `Sinks.Many` 管理 session 级事件总线。  
3. 在事件存储中保存 `seq/turnId/eventType/payload`，支持回放。  
4. 对 `/messages` 做 token 维度限流与审计。  
5. 通过 `X-Request-Id` 串联日志、指标、审计。  

---

## 待下一版本补充

- [ ] `GET /sessions/{id}/history` 历史消息分页  
- [ ] 会话暂停/恢复  
- [ ] 结构化输出 schema（评分、建议列表）  
- [ ] 第三方 Agent 回调签名的强制校验  

---

**文档维护**: QuestionOS Team  
**最后更新**: 2026-03-24 18:30
