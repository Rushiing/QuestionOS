# QuestionOS 阿里云迁移准备

> 现役状态（2026-10-09）：https://questionos.daydreamer.world 已切换至 Pre-RICH，用户旧账号与历史验收通过。最终状态、403修复和 Railway 退出条件见[切换执行单](cutover-plan-20261009.md)。下文为演练历史，不能作为当前状态。

历史演练状态：Pre-RICH 隔离演练已部署并恢复备份，见 rehearsal-results.md；未正式切换网站 DNS。QuestionOS 先迁，CyberMOMO 后迁。目标机是 Pre-RICH，必须保留 NewRICH 两个 systemd 服务与宿主机 Caddy。

## 基线（2026-10-08）

- Railway backend/frontend/smoke-monitor 均运行 `ecadf090a2d683fb872ac463acc819de06d06530`。
- 数据库为 PostgreSQL 18，500 MB 是卷额度，不是备份大小。
- 已有邮箱密码登录；Google-only 账户的邮箱认领实现见 `login-continuity.md`。
- Pre-RICH 到 Google token/tokeninfo/accounts 接口复测超时；百炼无凭据 GET 返回 401。只证明百炼 HTTP 可达，不证明当前配置的实际模型及凭据可用。
- CLI 授权已恢复，官方 SSH 只读盘点及本机私有备份已完成。公网数据库端口超时，目标配置交接仍需完成。不要用开发 .env 猜测线上配置。
- Railway 近 7 天、600 秒采样内存平均合计约 1.30 GB；宿主机之前可用约 2.42 GiB。需要结合 NewRICH、后续 CyberMOMO 验证峰值。

## 部署隔离

- Compose 只运行预构建镜像；在独立构建机编译 Next/Java。
- 显式使用项目名 `questionos-rehearsal` 或 `questionos-prod`，卷按项目隔离。两套前端不要同时启动：共用回环端口 13000/13001。
- 数据库无宿主端口，只连接内部 data 网络；backend 连接 app/data 两个网络，前端和 monitor 只连接 app。
- localhost:13000 为前端，13001 为监控。不要公开 monitor、actuator 或数据库。
- Caddy 片段只追加一个站点，不能覆盖现有 NewRICH 配置。变更前核对端口、合并后 validate，再经批准 reload。
- 内存上限合计 2816 MiB，不是资源预留或容量证明。保留已有 JVM 1 GiB 堆上限；压力下可用内存持续不足 768 MiB、发生 OOM/明显换页或影响 NewRICH，即停止切换并评估扩容。
- monitor 默认为内部链路且不写入、不调用 LLM；正式切换后将前端目标改成公网 origin，并另做外部验收。同机 monitor 不能发现整机断网。

## 配置与构建

将 `environment.example` 复制到 checkout 外的私有文件并 chmod 600。dotenv 值含 `$`、`#` 等字符时正确单引号包围；不要输出真实 Compose config 或提交密钥。

保留 JWT secret、sandbox token、Google client ID、模型端点/模型名/参数，目标库使用独立密码。核实有效配置后只保留正常 `QUESTIONOS_SANDBOX_TOKEN`，不照搬末尾带制表符的同名变量。前端只获得公开 client ID，不注入数据库或模型凭据。

以下为独立构建机上的命令，尚未执行。`QOS_RELEASE` 必须是包含已验收认证改动的实际发布 SHA，不能把旧 SHA 标在新镜像上：

```bash
docker build --platform linux/amd64 -f Dockerfile.railway-backend -t questionos-backend:$QOS_RELEASE .
docker build --platform linux/amd64 -f Dockerfile.railway-frontend -t questionos-frontend:$QOS_RELEASE .
docker build --platform linux/amd64 -f Dockerfile.railway-smoke-monitor -t questionos-monitor:$QOS_RELEASE .
```

记录镜像 ID/摘要和平台，不向未经确认的公开 registry 推送。PostgreSQL 18 也需固定到实际验收的摘要；tag 不能代替版本证据。

```bash
docker compose --env-file /PRIVATE/questionos.env -p questionos-rehearsal -f deploy/aliyun/compose.yml config --quiet
```

只使用 `--quiet`，普通 config 会展开密钥。必需配置缺失时应失败。

## 数据演练（配置交接与目标演练授权完成后）

1. 私有 PG* 环境变量指定 Railway QuestionOS 源库，用 PostgreSQL 18 客户端运行 `backup-database.sh NEW_PRIVATE_DIRECTORY`。脚本不写源库；备份是敏感数据，禁止提交 Git。
2. 记录 dump SHA256、扩展、Flyway checksum、账号/会话/消息数量、Google-only 与重复邮箱聚合。inventory 不输出邮箱、哈希或会话内容。
3. dump 与后续 inventory 是不同的一致性快照。演练期间源库继续写，计数差不一定是丢数据；正式停写后才比较精确计数。
4. 核实目标为 `questionos-rehearsal` 的新卷，只启动 postgres。执行 `SELECT count(*) FROM pg_tables WHERE schemaname='public'` 确认空库；非空就停止，不自动 drop/clean。
5. 仅对已核实的空目标执行 `pg_restore --exit-on-error --single-transaction --no-owner --no-acl`，不使用 `--clean`。再以 inventory 核对数据和迁移版本。
6. 恢复通过后先验证 V7 邮箱唯一约束及认领表迁移；本分支已有认证改动，不能继续当作原版本无 schema 变更迁移。先用回环/SSH 入口验证，真实账号写入与模型调用按实际授权范围执行。
7. 专用测试账号验收密码登录、历史、CALIBRATION/SANDBOX、SSE、长请求、重连；临时记录有标记，清理前列具体范围。
8. 验证重启持久化、备份定时器、OSS 上传及隔离恢复。本次仅提供导出工具，未安装备份 timer。

## 切换与回退

- 每次云端动作核对主机、Compose 项目、源目标数据库和授权范围，不使用 RICH 目录、数据库或密钥。
- 短暂停写、等待模型回合结束，最终导出、空目标恢复、精确核对；新旧不能同时接受写入。
- 原 `.up.railway.app` 托管域名不能直接解析到阿里云；需要新入口，必要时留短期跳转。旧 API 不得继续写旧库。
- localStorage 不跨域，用户在新域名需重新登录；保留 JWT secret 不会自动迁移浏览器会话。Google-only 用户可在新站上线后随时验证邮箱设置密码，不依赖旧站继续运行。
- 经批准发布 Caddy/DNS/登录配置，四服务和真实路径验收后观察至少一天。
- 新库无新增写入时可停止目标、恢复旧入口；已有新写入时，必须先停写并备份新库，评估回迁，不能直接启用旧库。
- 两个应用都验收后再退出 Railway；删除卷、备份和临时环境另列清单确认。

## 验收状态

配置解析、脚本检查与云端验收分开。生产导出、本地认证联调、目标隔离容器启动/恢复/重启验收已完成；独立测试邮件已实际收到。真实用户认领、真实模型、并发容量和正式切换尚未完成。真实模型/OAuth 不进入确定性 CI。

## 2026-10-08 后续准备进展

用户已验收独立测试库登录与历史；模型单次连通性通过，本地依赖升级候选和备份恢复通过。详细证据见 pre-cutover-validation.md、backup-recovery.md；候选依赖尚未更新服务器镜像，应用真实模型仍禁用。
