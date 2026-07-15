# QuestionOS 交付流程

## 1. 任务开始

每个任务先确认目标、不做什么、验收标准、影响层与风险级别。开始前运行 `git status -sb`。

- 工作区干净：从 `main` 创建 `codex/<type>-<task>` 分支。
- 工作区有无关改动、需并行开发或属于中高风险：从 `main` 创建独立 worktree。
- Agent 不得擅自 stash、删除或吸收用户的无关改动。

## 2. 风险分级

| 级别 | 例子 | 合并前 | 生产权限 |
|---|---|---|---|
| 低 | 文案、文档、无状态样式、局部确定性修复 | CI 全绿 | Agent 可准备 PR 和验收证据；合并仍由用户确认 |
| 中 | API / SSE、Prompt 行为、认证非关键路径、smoke、部署配置 | CI 全绿 + 用户确认 | Agent 不自行合并、改 Railway Variables |
| 高 | DB migration、权限、密钥、生产数据、网络 / region / scale、删除资源 | 回滚 / 备份方案 + 用户逐项确认 | 合并、部署、变量、数据操作都需用户确认 |

## 3. 验证矩阵

- Python legacy API：`cd v0.2/backend && ruff check app tests && pytest -q`
- Java Agent/API：`cd java-backend && mvn -DskipTests compile && mvn test`
- Next frontend：`cd v0.2/frontend && npm run typecheck && npm run build`
- 核心联调：`bash scripts/ci-core-smoke.sh`，只验证本地首页、proxy 401、Java health、session 创建与 SSE replay。

真实 Google OAuth、真实 LLM 全量评测、生产数据分析不进入基础 CI。它们属于手动或低频验收。

## 4. Prompt、Mock、Seed 与 Summary

- Prompt：基础 CI 只测输出 schema、解析、fallback 和关键路由；真实模型评测手动触发，必须有固定样本和预算上限。
- Mock：`v0.2/server.py` 的 mock response 仅是 legacy 本地能力，不代表生产 Java Agent。
- Seed：当前没有可支持的生产 seed pipeline。新 seed 必须幂等、显式标记测试数据、提供清理命令，且默认禁止指向 production。
- Summary：`CALIBRATION` 的 `synthesis` 与 `SANDBOX` 的 integrator 属于对话输出。先用 deterministic fixture 保护 JSON 解析和展示合同，不在每次 PR 上强制真实 LLM 质量评分。

## 5. PR、合并与部署

1. PR 填写任务边界、风险、验证证据、部署、回滚与 truth-sync。
2. 基础 CI 必须全绿。`main` 只允许通过 PR 合并，推荐 squash merge。
3. Railway 自动部署后，记录 commit SHA 与四个服务的 deployment ID。
4. 分别验收 frontend、backend、Postgres、smoke-monitor，不使用项目整体 Online 代替。
5. 最后验收真实用户路径：登录 / 测试账号 → 模式入口 → 对话 → Summary / integrator → history 恢复。

## 6. Truth sync 与清理

- `AGENTS.md`：只保留长期约束。
- `AGENT_PROJECT_BRIEF.md`：当前架构、入口与真实服务关系。
- 部署 runbook：当前 Railway 配置。
- PR：本次可复现验证证据。
- Agent 记忆不得保存密钥、生产数据或易过期状态。

用户确认线上验收后，再删除远端分支、本地分支、worktree、preview / 临时服务和临时测试数据。
