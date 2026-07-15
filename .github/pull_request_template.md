## 任务边界

- 目标：
- 不做：
- 风险：`low` / `medium` / `high`
- 影响层：frontend / python-legacy / java / SSE / DB / prompt / smoke / deploy / docs

## 验收证据

- [ ] Python `ruff check app tests && pytest -q`
- [ ] Java `mvn -DskipTests compile && mvn test`
- [ ] Frontend `npm run typecheck && npm run build`
- [ ] Core smoke（不调用真实 OAuth / LLM）
- 其他：

## 部署与回滚

- Railway 服务：
- 环境变量 / migration：无 / 说明
- 回滚方式：
- 线上验收：frontend / backend / Postgres / smoke-monitor / 真实用户路径

## Truth sync

- [ ] API / SSE / 配置文档已同步（若受影响）
- [ ] `AGENTS.md` 只记录长期规则（若受影响）
- [ ] 未将密钥、生产数据或易过期状态写入文档 / Agent 记忆
