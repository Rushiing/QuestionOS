# 国内登录与旧账户连续性

状态：用户已确认邮箱验证方案，已完成本地实现，未部署。用户已购买并开通阿里云邮件推送；发信域名已通过 DNS 验证，发件地址已创建，SMTP 认证和独立连接测试邮件已通过，用户已确认独立测试邮件收到；应用级 HTTP 认领链路已用隔离数据通过，国内线上用户路径未验收。

## 已确认的行为

旧账号与历史全部迁移，保持原 user_id/provider。Google-only 用户在新站 `/recover` 输入原邮箱，收到一次性验证口令后设置密码；原历史无需复制或重新关联。旧站下线不影响后续认领。

同邮箱已有账号时禁止另建密码账号；需要全新账号使用另一个邮箱。已有密码的用户继续密码登录；本次不增加通用密码重置。不能访问原邮箱时不允许人工勾选或仅凭邮箱认领，历史继续保留。

2026-10-08 只读盘点：89 个账户中 Google 无密码 88、password 有密码 1；当时 lower(email) 重复组为 0。V7 使用 lower(btrim(email))，发布前必须按新规范复核；发现冲突则停止，不自动归并。

## 接口与凭据

- `POST /api/auth/email-claim/request`：`{email}`；未知、已认领或限流账户返回相同文案，不发送邮件。
- `POST /api/auth/email-claim/confirm`：`{email, token, password}`；成功沿用原账号签发 JWT，进入历史页。
- 口令由 SecureRandom 生成 256 位随机值（43 个 URL-safe 字符），只放在邮件正文，不拼到 URL、不写日志。数据库仅存 SHA-256 摘要，10 分钟过期，一次性消费。
- 每账号发送间隔至少 60 秒、每小时最多 5 次，全站每小时最多 30 次。计数保存在 PostgreSQL，重启/多实例不会重置；失败投递也占额度，失败对应口令失效。
- 发送新口令使旧口令失效。发送/认领锁同一账户行；密码写入与口令消费同一事务，失败一起回滚。邮箱改变后原口令不可用于新邮箱。
- 密码沿用 BCrypt；密码查询不限 provider，Google upsert 保留已有密码。新密码至少 6 字符且 UTF-8 不超过 72 字节。
- sandbox token、邮箱文字或自我声明不能代替验证口令。

## 发信与部署前提

后端使用 SMTP STARTTLS（默认 587），强制 TLS 和证书主机名验证，连接/读/写超时各 5 秒。配置 `QUESTIONOS_SMTP_HOST/PORT/USERNAME/PASSWORD` 和 `QUESTIONOS_CLAIM_MAIL_FROM`；凭据只进后端私有配置。未配置时恢复接口返回 503，不假装邮件已发送。邮件连通性不加入基础健康检查，需单独验收。

阿里云邮件推送杭州区域已开通，`mail.daydreamer.world` 的四条 DNS 记录已生效且控制台验证通过；发件地址 `accounts@mail.daydreamer.world` 已创建并显示正常，SMTP 凭据已由用户设置；Pre-RICH 独立测试邮件发送成功，供应商日志返回 250 Send Mail OK，尚未验收 Java 应用认领发信路径。阿里云邮件推送支持杭州 `smtpdm.aliyun.com` 的 80 端口 STARTTLS，适配当前实现时将端口设为 80，并保留强制 STARTTLS；默认 587 不是该服务的端口。参见[官方 SMTP 文档](https://help.aliyun.com/zh/direct-mail/smtp-endpoints)。SMTP 适配参考 [Spring Boot 邮件文档](https://docs.spring.io/spring-boot/reference/io/email.html)。发信域名、SPF/DKIM 和投递验收应在切换前完成。Google 邮箱用户仍需能够访问自己的收件箱。

## V7 迁移与回退

V7 增加非空规范化邮箱唯一索引、认领表和全局发信额度表，不改变旧 user_id/provider/历史。先对恢复出的隔离库验证冲突和迁移，再经批准用于目标生产库；保留迁移前备份。

一旦有人完成认领，不能直接回退到只允许 provider=password 登录的旧 AuthService，否则这些用户会再次无法用密码登录。回退需保留新的密码查询逻辑并禁用发信入口；不要删除已经添加的密码或认领数据，不把旧备份覆盖到有新写入的库。

## 验证

单元测试覆盖随机口令摘要、失败投递、身份连续性、密码登录、注册冲突、无效口令与 UTF-8 密码长度。HTTP 测试覆盖校验、错误映射与原身份返回。隔离 PostgreSQL 18 测试覆盖 V1–V7、原历史归属、过期/错邮箱/错口令、限流、重发失效、并发单次认领、失败事务回滚、Google upsert 保留密码与邮箱唯一约束。

独立真实邮件投递已通过；目标服务器镜像、网站 DNS/HTTPS、真实账号认领和历史页面仍需部署前后实际验收；本地测试不替代这些检查。
