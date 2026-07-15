# Prompt、Mock、Seed 与 Summary 质量流程

## 支持边界

- 生产主链路：Java backend + `v0.2/frontend`。
- `v0.2/backend`：legacy Python API，只保留 import、基础 API contract 与迁移参考；不代表生产 Agent 行为。
- `v0.2/server.py`：legacy 本地 mock server，可用于旧 UI 演示，但不作为 Java Agent、SSE、Summary 或 Railway 的验收证据。

## 确定性基础检查

基础 CI 不调用真实 OAuth、LLM 或生产数据：

- Java `PromptContractFixtureTest`：Calibration JSON、phase label、单问展示、Step 1 parser fail-closed。
- Java `SandboxSceneRoutingTest`：各审议室 routing context。
- Java `IntegratorPromptContractTest`：核心议题、用户事实、问答绑定、前序观点与最终 integrator 指令。
- Frontend `npm run test:contracts`：Calibration JSON → Markdown 与 integrator 专家段落 golden cases。

Fixture 只写稳定契约，不固定模型措辞。

## 本地 Seed / 清理

启动 Java + frontend 后：

```bash
QOS_FIXTURE_TOKEN=sk-sandbox-dev node scripts/questionos-fixtures.mjs seed
QOS_FIXTURE_TOKEN=sk-sandbox-dev node scripts/questionos-fixtures.mjs status
QOS_FIXTURE_TOKEN=sk-sandbox-dev node scripts/questionos-fixtures.mjs clean
```

规则：

- manifest 记录本轮创建的 session ID；重复 `seed` 会复用，不会重复制造数据；
- `clean` 只删除 manifest 中的测试 session；
- 远程目标默认拒绝写入；必须在用户明确确认后同时设置 `QOS_FIXTURE_ALLOW_REMOTE=1` 与精确确认口令；
- 命令不发送消息、不调用 LLM。

## 低频真实 LLM Eval

固定样本位于 `evals/questionos-core.json`。执行时记录 commit、model、prompt version、case 结果与清理状态：

```bash
QOS_EVAL_URL=http://127.0.0.1:3000 \
QOS_EVAL_TOKEN=sk-sandbox-dev \
QOS_EVAL_MODEL=<model-name> \
QOS_EVAL_MAX_CASES=2 \
node scripts/questionos-llm-eval.mjs
```

- 默认最多 2 case，硬上限 4；不进入 required CI。
- 每个 case 创建独立 session，结束后无论通过失败都执行删除。
- 远程/生产执行需要用户确认及精确确认口令。
- Eval 只验证结构、单问、禁用措辞等自动契约；效果判断仍由人工查看样本输出。
- 修改核心 Prompt、phase 逻辑或 Summary/integrator 指令属于高风险任务：开工、合并与生产 eval 分别确认。

## Summary / Integrator 契约

- CALIBRATION `synthesis` 必须保留一个确认问句，并把 `user_conclusion_mirror` 显示为“你的结论（回放）”。
- SANDBOX integrator 必须携带核心议题、用户中途补充事实、问答绑定、前序观点和已问问题；输出由前端 normalization 保持专家段落可读。
- Golden cases 只保护 schema、解析和必要结构，不把某次模型全文当成永久真相。
