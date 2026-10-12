# 阿里云邮件推送接入 — 2026-10-08

> 历史阶段材料。当前部署与验收以[正式切换执行单](cutover-plan-20261009.md)为准；本文的“当前/待验收”均指记录当时。
已从当前登录控制台确认邮件推送已开通，区域 cn-hangzhou。资源包购买成功；尚未核对额度和有效期，不据此承诺可用发送量。

已创建发信域名 `mail.daydreamer.world`（ID 572090），已在控制台验证通过。
已创建发件地址 `accounts@mail.daydreamer.world`，类型为触发邮件，控制台状态正常。显示名 QuestionOS 在应用 SMTP From 配置中设置；控制台创建表单无显示名字段。

## 已批准的 DNS 记录（全部已添加并验证）

记录归属 `daydreamer.world`，全部限制在 mail 子域名：

| 类型 | 主机记录 | 值 |
|---|---|---|
| TXT | aliyun-cn-hangzhou._domainkey.mail | v=DKIM1; k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCWk028+n6RMV3PfLwSdDfZ7ltQowEiPxU7E/ZU89InfYnEbAB3bLNZq9oB9eN61l7dmlaSi7565sUv97Xn3L3GJi19AYCwJR3hc9zI02NQEd6RMsnDgsDMvvnMqAFZts17vZ5MN3HY56qR3f3blLJWcItX7PNQQdLLb0wVJqtYfQIDAQAB |
| TXT | mail | v=spf1 include:spf1.dm.aliyun.com -all |
| TXT | _dmarc.mail | v=DMARC1;p=none;rua=mailto:dmarc_report@service.aliyun.com |
| MX | mail | mx01.dm.aliyun.com |

这些值来自本账户域名配置页面，DKIM 是公开 DNS 公钥，不是 SMTP 密码。DMARC 汇总报告会发送给阿里云。MX 优先级使用控制台默认值 1；不修改主域名 MX、网站 A 或 CNAME。

## 连接验证

Pre-RICH 经 Python smtplib 连接 `smtpdm.aliyun.com:80`，STARTTLS 成功，证书使用默认信任链和主机名校验，协商 TLSv1.3，服务器公布 AUTH 能力。仅 EHLO/STARTTLS/QUIT，未认证、未发送邮件。

2026-10-08 17:09 左右从 Pre-RICH 使用 dig 回读 SPF、DKIM、DMARC 和 MX，四项与上表一致（MX 优先级 1）。DirectMail 域名列表显示“验证通过”；发信地址列表显示“正常”。

## 剩余步骤

1. DNS 写入、公开解析回读、DirectMail 验证已完成。
2. 发件地址已创建，SMTP 密码已由用户设置并保存到 Git 外私有文件，认证通过。
3. 必要凭据保存到用户批准的私有文件，后端配置端口 80 且保持强制 STARTTLS。
4. 已向指定邮箱发送唯一 1 封连接测试，供应商日志显示成功。区分 SMTP 接受、发送日志结果和用户收件确认；不自动重发结果未知的邮件。
5. 真实账号认领、部署与 V7 生产迁移仍单独验收。当前没有修改生产应用服务；四条邮件 DNS 记录已完成。

参考：[官方 SMTP 端点](https://help.aliyun.com/zh/direct-mail/smtp-endpoints)、[发信域名配置](https://help.aliyun.com/zh/direct-mail/user-guide/how-to-configure-sending-domain-names)。

用户已确认上述 4 条 DNS 记录及发件地址。DNS 控制台原有 6 条 A 记录，无邮件配置冲突；四条邮件 DNS 记录已全部添加，原有网站记录未修改。用户已指定测试收件邮箱，授权仅 1 封连接测试，已发送，详见下方实测。

## 2026-10-08 SMTP 实测

用户完成密码设置并保存至 Git 外私有文件（权限 0600）。通过 SSH 标准输入临时传递给 Pre-RICH 的测试进程，未写入服务器文件、命令参数或日志。Pre-RICH 使用 smtpdm.aliyun.com:80，证书验证的 STARTTLS（TLSv1.3）认证成功；仅发送已授权的 1 封“QuestionOS 邮件连接测试”，SMTP 已接受。邮件不含真实账号数据或认领口令。阿里云发送详情在 2026-10-08 17:14:45 显示该唯一测试邮件“成功”，详情“250 Send Mail OK”；用户已明确确认收到测试邮件。测试使用 Python smtplib，尚不代表 Java 应用实际发信路径验收。
