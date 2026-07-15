## 任务边界

- 目标：
- 不做：
- 风险：`low` / `medium` / `high`
- 影响层：frontend / python-legacy / java / SSE / DB / prompt / smoke / deploy / docs
- 用户确认：无需 / 已确认开工 / 待确认合并 / 待确认生产操作

## 验收证据

- [ ] Python `ruff check app tests && pytest -q`
- [ ] Java `mvn -DskipTests compile && mvn test`
- [ ] Frontend `npm run typecheck && npm run build`
- [ ] Frontend contract tests `npm run test:contracts`
- [ ] Core smoke（不调用真实 OAuth / LLM）
- 其他：

## 部署与回滚

- Railway 服务：
- 环境变量 / migration：无 / 说明
- 回滚方式：
- 线上验收：frontend / backend / Postgres / smoke-monitor / 真实用户路径
- 回滚 deployment / checklist：

## 高风险专项（仅适用时）

- [ ] 真实 OAuth / LLM / production data 操作已单独获用户确认
- [ ] Prompt/model/version/case budget 已记录
- [ ] Seed / eval / 临时 session 已执行清理

## Truth sync

- [ ] API / SSE / 配置文档已同步（若受影响）
- [ ] `AGENTS.md` 只记录长期规则（若受影响）
- [ ] 未将密钥、生产数据或易过期状态写入文档 / Agent 记忆
