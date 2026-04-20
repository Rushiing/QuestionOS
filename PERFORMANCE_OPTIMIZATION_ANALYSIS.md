# 前台模型返回慢：性能优化分析

## 🔍 当前性能瓶颈识别

### 1. **后端 LLM 超时配置过长**（**P0 - 立即优化**）
- **当前值**：`QUESTIONOS_LLM_TIMEOUT_SECONDS=240`（4 分钟）
- **问题**：
  - 用户每次调用 LLM 最多等待 240s（+响应网络延迟）
  - 即使模型在 10-30s 内完成，后端仍可能"白等"
  - 思维校准追问、沙盘分诊等操作都用这个 timeout
- **表现**：用户感知的"慢"往往源自这个长 timeout，而非真实推理时间

### 2. **SSE 连接超时过短**（**P0 - 可能导致连接断裂**）
- **当前值**：`DEFAULT_SSE_CONNECT_TIMEOUT_MS=60_000`（60 秒）
- **问题**：
  - 仅限制"建立连接"耗时，不限制后续流式读取
  - 如果 Java 后端响应比较慢（LLM 还在思考），前端 60s 可能不够
  - 网络不稳定时容易触发超时
- **合理值**：应该与后端 LLM timeout 同步，或更长（因为包含网络延迟）

### 3. **sendMessage 超时配置**（**P1 - 改进用户体验**）
- **当前值**：`45_000`ms（45 秒）
- **问题**：
  - POST /messages 本身通常很快（几百 ms），主要时间在后续 LLM 调用
  - 但这个 timeout 只覆盖 HTTP 请求本身，不包括 SSE 流式读取
  - 用户可能在这里也遇到等待
- **改进**：可以改短到 10-15s（POST 本身足够），因为真正的等待在 streamTurn

### 4. **流式 vs 非流式权衡**（**P1 - 需要数据驱动决策**）
- **当前配置**：`streamChatCompletions=true`（默认流式）
- **问题**：
  - 流式的好处：用户能更早看到第一个字符（TTFB - Time to First Byte）
  - 流式的坏处：增加网络请求数、解析 SSE 的开销
  - 对于简短输出（如分诊结果、追问），非流式可能更快
- **现象**：
  - 分诊（SandboxSceneClassifier）：输出很小（JSON 一行），流式可能反而慢
  - 思维校准追问：输出较小（一个问句），流式可能反而慢
  - 沙盘审议：输出很大（多段 Markdown），流式确实有优势

### 5. **Payload 体积和重复请求**（**P2 - 优化后台效率**）
- **buildSandboxStep1UserPayload** 中：
  - 包含完整的对话摘录（USER_TRANSCRIPT_MAX=1200 字）
  - 多轮追问时，摘录重复包含，没有增量式追加
  - 新加的维度库提示也是每次完整发送
- **优化方向**：
  - 对于纯追问问题，可以只发 delta（本轮新消息 + 维度提示），不重复全历史
  - 或者在 LLM 侧用 system cache（OpenAI 提供的上下文缓存）

### 6. **维度库注入的 Prompt 大小**（**P2 - 微优化**）
- **当前**：每次调用都在 payload 中重新生成和注入完整维度库文本
- **优化方向**：
  - 维度库可以作为 system prompt 的一部分缓存
  - 而非每次都在 user message 中拼接

---

## 📊 性能指标参考

根据当前配置和代码，用户可能遇到的延迟分布：

```
发送消息 → POST /messages ……………………… ~0.5s（快）
                                  ↓
           后端分诊与追问 LLM ……… 10-30s（取决于模型）
                                  ↓
           后端等待超时上限 ……… 240s（问题！）
                                  ↓
        前端 SSE 流式读取 ……… 10-30s（取决于输出量）
                                  ↓
        前端 SSE 连接超时上限 …… 60s（可能不够！）
                                  ↓
            总耗时 ……………………… 20-60s（正常），最坏可能 240s+
```

---

## 🚀 优化建议清单（优先级排序）

### P0 - 立即改善用户体验（今天）

#### 1️⃣ **降低 LLM 超时阈值（针对快速操作）**
```
分诊（SandboxSceneClassifier）：     30s（而非 240s）
追问（Step1Clarify）：              60s（而非 240s）  
思维校准（Calibration）：           120s（而非 240s）
```

**实现方式**：在 `OpenClawInvokeService` 中为不同的调用场景设置不同的 timeout override。

```java
// 伪代码示例
sandboxSceneClassifier.classifyDetailed(issue)  
  // 内部改为 invokeDefaultLlmCompact(..., timeoutSec: 30)

mainCalibrateAgent.generateSandboxStep1ClarifyFollowup(...)
  // 内部改为 invokeDefaultLlmCompact(..., timeoutSec: 60)
```

**预期效果**：用户不再卡在 240s，快速操作可能在 20-40s 完成。

#### 2️⃣ **同步前端 SSE 超时与后端 LLM 超时**
```
当前：SSE connectTimeout = 60s
改为：SSE connectTimeout = 270s（240 + 30 缓冲）
```

**位置**：`v0.2/frontend/lib/sse-client.ts`
```typescript
const DEFAULT_SSE_CONNECT_TIMEOUT_MS = 270_000; // 之前 60_000
```

**理由**：SSE 连接可能等待后端 LLM 完成，不能比 LLM timeout 短。

---

### P1 - 改进流程效率（本周）

#### 3️⃣ **为不同操作调整流式策略**
```
分诊输出（JSON 一行）：       关闭流式，改为整包等待（更快）
追问生成（一个问句）：       关闭流式，改为整包等待（更快）
沙盘审议（多段 Markdown）：  保持流式（TTFB 优先）
思维校准（长文本）：          保持流式（TTFB 优先）
```

**实现方式**：在 `OpenClawInvokeService` 中按场景选择：
```java
// 分诊专用调用方法（非流式）
public Mono<String> classifyDetailed_Fast(String issue) {
    // 改为 invokeOpenAICompatibleRaw()，不走 streamChatCompletions
}

// 追问专用调用方法（非流式）
public Mono<String> generateStep1Clarify_Fast(...) {
    // 改为非流式
}
```

**预期效果**：
- 分诊从 "10-30s 流式拼接" → "5-15s 整包返回"
- 追问从 "8-20s 流式拼接" → "4-10s 整包返回"

#### 4️⃣ **优化 Payload 体积（Prompt 工程）**
```
当前维度库提示：在 buildSandboxStep1UserPayload 每次注入完整文本
改为：
  - 维度库做成 system prompt 的一部分，缓存在 LLM 侧
  - user message 中仅传递 delta（本轮新信息 + 场景 ID）
```

**预期效果**：请求体积减少 30-40%，网络传输更快。

---

### P2 - 深度优化（后续）

#### 5️⃣ **启用 OpenAI 上下文缓存（如果用 OpenAI）**
```
维度库 + 系统 prompt → 作为缓存块
用户历史摘录 → 缓存块
```

**前提条件**：使用 OpenAI GPT-4 Turbo 或更新模型。
**预期效果**：重复请求可以节省 50% token 成本和时间。

#### 6️⃣ **并行化多个轻量级 LLM 调用**
```
当前流程（串行）：
  1. 分诊 ……… 20s
  2. 语义点火 …… 15s
  3. 生成追问 …… 18s
  总计 ……… 53s

优化后（并行）：
  1. 分诊 ✕ 并行调用
  2. 语义点火 同时进行
  预期 ……… 30s（按最慢的算）
```

**局限**：需要保证逻辑独立性，当前分诊→语义点火→追问是依赖链，难以并行。

---

## 📈 优化前后对比（预期）

| 场景 | 当前耗时 | 优化后耗时 | 改善 |
|------|---------|----------|------|
| 问题不够具体→追问 | 40-60s | 15-25s | **↓ 60-70%** |
| 分诊 + 语义点火 | 30-50s | 15-25s | **↓ 40-50%** |
| 进入步骤②（快模型） | 25-40s | 15-25s | **↓ 20-30%** |
| 进入步骤②（慢模型） | 60-120s | 40-80s | **↓ 20-35%** |

---

## 🔧 实现建议（代码层）

### 立即可做（2-3 小时）

1. **调整 timeout 常数**
   ```java
   // OpenClawInvokeService.java
   private static final int CLASSIFY_TIMEOUT_SEC = 30;      // 之前无独立配置
   private static final int STEP1_CLARIFY_TIMEOUT_SEC = 60;  // 之前无独立配置
   private static final int SEMANTIC_IGNITION_TIMEOUT_SEC = 18; // 已有
   ```

2. **调整前端超时**
   ```typescript
   // runtime-config.ts
   const DEFAULT_SSE_CONNECT_TIMEOUT_MS = 270_000; // 之前 60_000
   ```

3. **为分诊和追问添加非流式选项**
   ```java
   // MainCalibrateAgent.java
   public String generateSandboxStep1ClarifyFollowup_NonStreaming(...) {
       // 改用 invokeDefaultLlmCompact(..., false) 禁用流式
   }
   ```

### 需要数据驱动（下周）

4. **收集性能指标**
   - 记录每个 LLM 调用的实际耗时（已在日志中，可以聚合）
   - 比较流式 vs 非流式的 TTFB 和总耗时
   - 基于数据调整策略

5. **A/B 测试不同 timeout 值**
   - 10% 用户用更激进的 timeout（分诊 20s 而非 30s）
   - 观察是否有明显的超时率上升

---

## 💡 快速验证清单

```bash
# 1. 检查当前超时配置
echo "当前 LLM timeout: $QUESTIONOS_LLM_TIMEOUT_SECONDS"

# 2. 查看后端日志里的实际 LLM 调用耗时
grep "invokeDefaultLlmCompact\|classify succeeded" *.log | grep -o "Xms"

# 3. 检查前端 SSE 超时配置
grep "DEFAULT_SSE_CONNECT_TIMEOUT_MS" lib/sse-client.ts

# 4. 测试一个完整流程的耗时
curl -X POST http://localhost:8080/api/v1/sandbox/sessions \
  -H "Authorization: Bearer sk-sandbox-dev" \
  -H "Content-Type: application/json" \
  -d '{"mode":"SANDBOX","question":"我在考虑创业"}'
# 记录响应时间
```

---

## 🎯 短期行动计划

**第 1 天**（今天）：
- [ ] 修改后端超时常数（P0 项 1）
- [ ] 修改前端 SSE 超时（P0 项 2）
- [ ] 本地验证不会引入新的 bug
- [ ] 推送到 Railway，观察实际效果

**第 2-3 天**：
- [ ] 收集用户反馈和性能数据
- [ ] 如果用户仍反映慢，考虑 P1 项 3（非流式策略）

**第 1 周**：
- [ ] 基于数据调整其他 timeout 值
- [ ] 考虑 Prompt 优化（P1 项 4）

---

## 相关配置参考

**后端**：
- `application.yml`：`questionos.llm.timeoutSeconds`
- `OpenClawInvokeService`：各个 invoke 方法的 timeout override 参数

**前端**：
- `runtime-config.ts`：`SANDBOX_TURN_MAX_WAIT_MS` (全局)
- `sse-client.ts`：`DEFAULT_SSE_CONNECT_TIMEOUT_MS`
- `sandbox-client.ts`：各个 `fetchJson` 调用的 `timeoutMs`

---

**建议优先从 P0 开始，可以在 1-2 小时内完成，预期改善 30-50% 的用户等待时间。**
