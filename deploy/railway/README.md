# QuestionOS Railway 生产 Runbook

本文只记录当前生产拓扑和可重复操作。不要整份导出 Railway Variables；只读取任务需要的变量名，任何 secret 不进入日志、PR 或 Agent 记忆。

## 1. 生产拓扑

| 服务 | Source / Root | Config as Code | Healthcheck | 区域 |
|---|---|---|---|---|
| `frontend` | GitHub `Rushiing/QuestionOS`, `/v0.2/frontend` | `/v0.2/frontend/railway.json` | `/`，60s | Singapore |
| `backend` | GitHub `Rushiing/QuestionOS`, `/` | `/railway.backend.json` | `/actuator/health/liveness`，300s | Singapore |
| `Postgres` | Railway PostgreSQL 18 image + `postgres-volume` | 平台资源 | 由 backend Hikari/Flyway 证明连通 | US West |
| `smoke-monitor` | GitHub `Rushiing/QuestionOS`, `/` | `/railway.smoke.json` | `/ready`，30s | US West |

正式入口：

- frontend：`https://questionos-app.up.railway.app`
- backend：`https://questionos-production.up.railway.app`
- smoke-monitor：`https://smoke-monitor-production.up.railway.app`

浏览器生产环境走同源 `/api`。`QUESTIONOS_BROWSER_API_URL` 与 `NEXT_PUBLIC_API_URL` 应留空；Next 容器通过 `INTERNAL_API_URL=http://questionos.railway.internal:8080` 访问 Java。

## 2. Watch paths

Railway watch paths 从仓库根 `/` 匹配，即使服务配置了 Root Directory 也不改变：

| 服务 | Watch paths |
|---|---|
| frontend | `/v0.2/frontend/**` |
| backend | `/java-backend/**`、`/Dockerfile.railway-backend`、`/railway.backend.json` |
| smoke-monitor | `/scripts/questionos-smoke-check.mjs`、`/scripts/questionos-smoke-monitor.mjs`、`/Dockerfile.railway-smoke-monitor`、`/railway.smoke.json` |
| Postgres | 不连接 GitHub，不设置 watch path |

若一次变更同时影响共享契约，PR 必须把所有受影响目录纳入变更，使对应服务都能触发部署。不要靠无关空提交触发生产。

## 3. Variables 边界

Backend 必需变量名：

```text
SPRING_PROFILES_ACTIVE=postgres
QUESTIONOS_SANDBOX_TOKEN
QUESTIONOS_ALLOWED_ORIGINS
QUESTIONOS_LLM_ENDPOINT
QUESTIONOS_LLM_API_KEY
QUESTIONOS_LLM_MODEL
PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD
```

Frontend 关键变量名：

```text
INTERNAL_API_URL=http://questionos.railway.internal:8080
NEXT_PUBLIC_API_VERSION=1.1
NEXT_PUBLIC_SANDBOX_TOKEN
INTERNAL_GOOGLE_CLIENT_ID
```

Smoke monitor 关键变量名：

```text
QOS_FRONTEND_URL=https://questionos-app.up.railway.app
QOS_BACKEND_URL=http://questionos.railway.internal:8080
QOS_MONITOR_INTERVAL_MS=60000
QOS_MONITOR_STALE_AFTER_MS=180000
QOS_MONITOR_FAILURE_THRESHOLD=1
```

高频 monitor 默认不设置 `QOS_SMOKE_CREATE_SESSION`。只有人工低频验收才临时启用 session/SSE；真实 LLM 还需额外启用 `QOS_SMOKE_RUN_LLM_TURN=1`。

## 4. 合并后的部署验收

先链接正确项目，避免误操作其他 Railway 项目：

```bash
railway link \
  --project e045a0c4-63c9-4fad-addb-c1980a849292 \
  --environment production
railway status
```

记录 merge commit 后运行：

```bash
QOS_RELEASE_COMMIT=<40-char-merge-sha> \
QOS_RELEASE_SERVICES=frontend,backend,smoke-monitor \
node scripts/questionos-release-evidence.mjs
```

`QOS_RELEASE_SERVICES` 只列本次应部署的 GitHub 服务。脚本会验证：

- 指向正确 Railway project；
- 受影响服务的 deployment commit 与 merge commit 一致；
- frontend 主页、登录、Google runtime config 与同源 API 401；
- backend 内部 health / Prometheus；
- Postgres volume、Hikari 与 Flyway 证据；
- smoke-monitor 的 `/ready`、`/health`、`/last`、`/metrics`。

证据可保存到 ignored 的 `output/`，摘要贴入 PR 或 GitHub Release：

```bash
mkdir -p output
QOS_RELEASE_EVIDENCE_OUT="output/release-<sha>.json" \
QOS_RELEASE_COMMIT=<sha> \
node scripts/questionos-release-evidence.mjs
```

真实用户路径仍需按任务风险补验：Google 登录、模式入口、Agent 对话、Summary/integrator、history 恢复。涉及真实 LLM 或生产写入前必须获得用户确认。

## 5. Postgres 跨区域判断

Postgres 当前在 US West，backend 在 Singapore。不要仅凭拓扑直接迁移。先从 monitor `/last` 的历史中记录至少 20 个 `backend.health` 样本，再结合真实 Agent 回合耗时判断数据库是否是主要瓶颈：

```bash
curl -fsS https://smoke-monitor-production.up.railway.app/last \
  | jq '[.history[] | .results[] | select(.name=="backend.health") | .ms]'
```

迁移属于高风险任务：必须另建备份/恢复/回滚方案并由用户逐项确认。

## 6. 回滚 Checklist

1. 记录失败 deployment ID、commit、受影响服务和首个错误时间。
2. 确认 migration 是否执行；若有 destructive migration，不得只回滚镜像。
3. 无 schema 风险时，在 Railway 对受影响服务 redeploy 上一个 `SUCCESS` deployment。
4. 恢复前一版 Variables / domain / region 只可使用事先记录的白名单字段，不使用整份配置导出。
5. 重新运行 `questionos-release-evidence.mjs`，并补真实用户路径。
6. 在 PR/Release 记录回滚 deployment ID 与残留数据影响。

## 7. 常见故障

- Backend 502：确认 Netty 监听 `0.0.0.0:$PORT`，healthcheck 为 `/actuator/health/liveness`，日志无 OOM。
- Frontend 502：确认 Root `/v0.2/frontend`、Config `/v0.2/frontend/railway.json`、容器监听 Railway `PORT`。
- Proxy 失败：确认 `INTERNAL_API_URL` 为 `http://questionos.railway.internal:8080`，浏览器 API base 保持空字符串。
- Smoke 502：确认 Config `/railway.smoke.json`、healthcheck `/ready`，脚本优先读取 Railway `PORT`。
- CORS：地址栏 origin 必须完整出现在 `QUESTIONOS_ALLOWED_ORIGINS`；先排除 502，再判断 CORS。
