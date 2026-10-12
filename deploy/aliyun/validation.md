# 准备阶段验证 — 2026-10-08

源代码基线：`ecadf090a2d683fb872ac463acc819de06d06530`。独立分支 `codex/questionos-aliyun-migration`；没有合并、推送、部署或覆盖原工作区。

通过：

- Pre-RICH 已安装 Docker Compose 对 stdin 配置的解析；仅使用 dummy 值、未写文件、未启动容器。
- 核对四服务、Postgres 内部网络、仅 frontend/monitor 回环端口、PG18 数据卷路径和 monitor 禁用写入/模型开关。
- 未提供必需配置时 Compose 非零退出。
- Pre-RICH 已安装 Caddy 对 stdin 站点片段的 adapt 解析；未 reload 或改在线配置。
- `bash -n deploy/aliyun/backup-database.sh`、`git diff --check`。
- 使用临时 stub 客户端验证导出脚本的五个拒绝路径：客户端版本不符、服务端版本不符、非 QuestionOS 库、禁用 TLS、已有输出目录。均在导出前停止，保留已有文件，不输出 dummy 密码。
- `npm run test:contracts`：3 个已有契约用例通过。
- `npm run typecheck`、`npm run build` 通过。使用本机 Node 25.6.0；生产镜像仍为 Node 20，需要在镜像构建时另验。Browserslist 数据过期警告未在本次迁移中顺手升级。

未验证：

- 本机没有 Docker daemon/CLI，尚未构建和运行 linux/amd64 镜像。已找到本机 JDK 21，并完成下述 Java 测试；认证代码已有修改。
- 公网数据库连接从本机和 Pre-RICH 超时；本机另有 DNS 异常。已通过 Railway 官方 SSH 执行只读 inventory。归档恢复与停写后的精确数据比较仍未完成。
- 已在私有进程核对模型 endpoint 为 dashscope.aliyuncs.com、模型为 deepseek-v4-flash；未调用真实模型。
- Google 登录接口从 Pre-RICH 复测超时；账户分布已读取，Google-only 邮箱认领已有本地实现，未发布、未验收真实投递。
- DNS/HTTPS、真实用户登录、SSE、并发容量、定时备份/OSS 和端到端恢复仍需上线前演练。

## 授权后的只读实测

- 用户完成 CLI OAuth 授权，`railway whoami` 成功；变量只在私有进程内处理，未输出密钥。
- Railway SSH 首次连接登记主机指纹后可用；数据库服务 PostgreSQL 18.6。本机 libpq 工具为 18.4。
- 只读事务：数据库 12,621,503 bytes，Flyway V1–V6 全部 success，扩展仅 plpgsql 1.0。
- 账户 89：Google 无密码 88、password 有密码 1；规范化邮箱重复组 0。
- 会话 8,543，消息 1,378。该统计是查询时快照，不代表所有会话均有消息或均为真实用户操作。
- 两个 sandbox token 变量（含尾部 tab 的异常名称）值相同；未更改线上变量。
- 已通过官方 SSH 将只读 pg_dump 流保存到本机私有目录 `~/Documents/服务器盘点/private-backups/questionos-20261008T160633/`。归档 503,402 bytes，文件权限 0600；目录不在 Git 中。归档目录读取、完整转 SQL 至 /dev/null 及 SHA-256 回读校验通过，尚未执行隔离数据库恢复。
- 本次没有修改生产账户、部署、DNS、定时任务或 Railway 服务状态。

## 邮箱认领实现验证（用户确认方案后）

- JDK 21 编译通过；`mvn test` 共 32 项，0 failures/errors/skipped，包括 7 项隔离 PostgreSQL 18.6 集成测试。随后补充 V6 已有 Google/密码账户升级 V7 的断言，7 项数据库测试复跑通过。
- 覆盖一次性口令、邮箱绑定、过期与重放、并发唯一成功、密码写入失败回滚、邮箱唯一索引、原会话 owner 不变、Google upsert 保留密码、SMTP 失败失效与安全错误、HTTP 参数校验。
- 前端 3 项 contracts、typecheck、production build 通过；`/recover` 生成成功。新增页面复核 label、状态提示、提交禁用、原邮箱锁定和认证状态同步。
- `scripts/ci-core-smoke.sh` 通过：首页、proxy 401、Java health、合成会话、SSE replay、fixture 幂等和本地清理。未调用真实 Google/LLM/SMTP 或生产库。
- 本地安装 PostgreSQL 18.6 用于隔离合成数据测试，未启用 brew 后台服务。测试数据库只监听 loopback，测试结束关闭；不包含生产备份。
- Flyway 现有依赖提示 PG18 超出其已测试最高版本（16）；实际 V1–V7 及 V6 升级 V7 已通过，未在本次升级 Flyway。Browserslist 旧数据警告仍存在。
- CI java-checks 已加入独立 postgres:18 service 与上述集成测试环境；仅修改本地 workflow，未推送、未运行 GitHub CI。
- 增加 SMTP 字段后，Pre-RICH 仅使用 dummy 值经 stdin 执行 Compose config --quiet 通过，无文件写入或服务变更。
- 尚无发信服务，真实投递/域名认证、目标镜像与线上用户认领未验收。未合并、部署、执行生产 V7、修改 DNS 或发送真实邮件。

## 2026-10-08 应用认领联调与备份恢复

- 33 项 Java 测试全部通过，无失败、错误或跳过。新增 `EmailClaimFlowPostgresTest` 启动真实 Spring Boot HTTP 服务和隔离 PostgreSQL，先生成 V6 合成旧账号/会话/消息，再由应用 Flyway 升级 V7。
- 完整链路通过：同邮箱注册拒绝 → 邮件认领请求 → 捕获测试邮件口令 → 认领 → 重放拒绝 → 错密码拒绝 → 新密码登录 → 原 user_id 的会话及消息读取 → 其他用户读取原历史返回 404。SMTP 在这项确定性测试中由 JavaMailSender mock 捕获，不向外发送。
- 首轮新增测试因合成消息 role 写成小写 user 被持久化读取器跳过；按存储契约改成 USER 后全部通过。未修改生产代码来迁就测试。
- 将 16:06 的私有备份完整恢复到新建的本机 loopback PostgreSQL 18.6 隔离库：账号 89、会话 8,543、消息 1,378；lower(btrim(email)) 重复组 0。
- 在恢复副本以单事务执行 V7 SQL 成功；对所有原业务表按行排序序列化并计算 SHA-256，迁移前后完全一致。此项是实际备份的 SQL 演练，未改写 Flyway 历史；Flyway V6→V7 另由应用联调验证。
- 邮件四条 DNS、发信地址已完成；Pre-RICH 17:14:45 的独立 SMTP 测试显示投递成功，用户已确认 Gmail 收到。仅一封，不含账号数据/口令。
- 仍未验证目标 linux/amd64 镜像、前端浏览器认领交互、Java 经真实 SMTP 发送认领邮件、正式用户在目标站认领、真实模型和容量。未部署或切换 QuestionOS；邮件 DNS 不代表网站切换。

## 本机 Linux 镜像验证（2026-10-08）

- Homebrew 安装 Docker CLI、Buildx、Colima；专用 profile `questionos-build` 为 VZ/Rosetta，2 核、4 GiB RAM、30 GiB 数据盘，不注册开机自启，不切换默认 Docker context。
- 源码快照 SHA-256 `fa0a54f49911a1bc488d4ce6dc6cfe3de702a1172214bb5514b131011a968904`，基于 ecadf09 加本地迁移改动，不冒充已提交发布 SHA。
- backend/frontend/monitor 三个 `rehearsal-fa0a54f49911` 镜像均构建并检查为 linux/amd64，已导出本机镜像归档。摘要、源码归档、镜像包及构建日志位于 Git 外 `~/.config/questionos/build/fa0a54f49911/`。
- 后端复用 JDK21 Maven package 的 JAR（SHA-256 `ef4a693eeeb0224d2475dd7dd40823d0abe6f70df3ca10d741e7dd8650f78fb8`），运行阶段沿用原 Dockerfile 的 JVM 参数和 profile 默认值，固定官方 Temurin 基础镜像摘要；仅组装方式不同。原多阶段构建已取消，未将其记作成功。
- 前端使用原 Dockerfile，在 Node20 Alpine linux/amd64 下 npm ci、Next production build 成功；monitor 使用原 Dockerfile。
- 在本机回环端口启动容器，后端使用 local profile、关闭持久化且不注入生产配置；首页、/recover、/login 为 200，/api/auth/me 为预期 401。监控核心 5 项通过，/health 为 ok=true、stale=false；合成会话/模型调用按配置跳过。
- 首轮前端未配置测试 Google Client ID 导致监控失败；另建配置完整的测试容器后通过。未调用真实 Google，不能视为 OAuth 验收。
- npm audit --omit=dev 在实际前端镜像报告 13 个受影响依赖节点（1 critical、7 high、2 moderate、3 low），不是 13 个已验证可利用漏洞。Next14.2.35 命中官方 AVIF/sharp RCE 和 Windows RCE 范围；当前镜像 sharp 缺席、运行平台 Linux，源码无 next/image 引用或 remotePatterns。未验证利用路径，不能仅以版本告警断言可利用；其余告警和修复范围仍需公开上线前评估。审计原始 JSON 已保存到上述构建目录。
- 官方 Next 公告：https://github.com/vercel/next.js/security/advisories/GHSA-2xp9-vwfh-vxw4 、https://github.com/vercel/next.js/security/advisories/GHSA-p293-qw3h-jr36 。没有自动进行跨大版本升级。
- PostgreSQL18.6 linux/amd64 官方候选摘要已解析，尚未拉取/启动该容器；本次数据库恢复使用本机 PostgreSQL18.6。
