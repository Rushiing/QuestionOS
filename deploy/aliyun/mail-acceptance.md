# 真实邮件认领验收

> 历史阶段材料。当前部署与验收以[正式切换执行单](cutover-plan-20261009.md)为准；本文的“当前/待验收”均指记录当时。
当前状态：用户已授权向 <用户指定的验收邮箱> 发送一封认领邮件。SMTP 已接入独立测试库，Java 认领请求已返回 200，测试库 send_count=1、口令未过期、模拟账号仍无密码。Gmail 连接器返回 RATE_LIMIT_EXCEEDED，真实收件及认领尚待验收；未自动重发。此前完成独立 SMTP 连接测试与模拟邮件的 Java 集成测试。

## 本次范围

仅作用于 Pre-RICH 的 questionos-rehearsal。沿用当前镜像和回环入口；原恢复库 questionos 保留不动，backend 暂时改连独立 questionos_mail_acceptance 数据库。为 backend 增加已有 access 网络以连接 SMTP；该桥接网络允许后端出站，不是仅限 SMTP 的网络白名单。模型地址仍为 127.0.0.1:9，真实模型凭据仍不配置。

SMTP 使用 smtpdm.aliyun.com:80、强制 STARTTLS 和主机名校验。现有私有凭据仅写入服务器 0600 配置，不写 Git、命令参数或输出。仅重建 backend，检查启动、邮件配置存在性（不显示值）及监控 /health。

## 测试账号与邮件

用户指定收件地址 <用户指定的验收邮箱>。只读检查确认原恢复库已有该邮箱，因此合成账号放入独立测试库。仅建立独立 Google 类型、无密码的合成账号，以及一条合成历史会话和消息；不修改已有真实账号。插入前检查该邮箱和固定测试 ID 均不存在，有冲突则停止，不覆盖。三条插入使用单事务。

发送内容为现有“QuestionOS · 恢复旧账号”邮件，包含十分钟有效、一次性的认领口令。只申请一封，不因未立即收到而自动重发。用户直接在演练站 /recover 填写口令和测试密码，不在聊天中提供凭据。

## 验收

1. /recover 请求后确认实际邮件收到；SMTP 接受不等于收件成功。
2. 用户填写口令与测试密码后进入 /history，看到合成历史。
3. 核对合成账号 user_id、provider 和会话所有者未变，password_hash 已设置，token_hash 已清空；不输出密码哈希或口令。
4. 用户退出并用测试邮箱与密码重新登录，仍可查看合成历史。无登录访问仍被拒绝。
5. 核对原账号、会话、消息指纹（排除明确的合成记录）未变，并检查容器状态、监控、内存与 NewRICH 进程连续性。

实际投递与浏览器操作未完成前，不标记“真实认领通过”。既有自动测试覆盖口令重复使用被拒绝、错误密码被拒绝和跨账号读取被拒绝。

## 回退

原 rehearsal.env 未修改。SMTP 凭据及测试库名称仅写入服务器 0600 的 mail-integration.yml。去掉额外的 mail-integration.yml 并重建 backend，即恢复原数据库、SMTP 禁用和后端内部网络。保留合成记录与验收证据，清理另行确认。不会回滚或覆盖数据库，不改网站 DNS、Caddy 或 Railway。

## 2026-10-08 实际结果

用户将实际收件口令保存到本机 0600 私有文件后，使用当前 Java 应用完成真实认领，HTTP 200。仅合成账号设置了随机测试密码，凭据保存本机私有文件，不输出到聊天或 Git。

- 模拟账号 ID 和 google provider 保留，原测试会话及消息正文一致。
- 邮箱密码登录成功；重复认领口令返回400、错误密码返回401、未登录访问返回401、其他测试账号读取历史返回404。
- 口令哈希已清空，发信次数为1，未重发。
- 首次验收脚本对空正文404错误地按JSON解析，修正测试脚本后复验通过；不是应用认领失败，也没有重新设置密码。
- Gmail 连接器限流未绕过；实际邮件口令由用户从收件箱提供。

当前页面仍连接独立 questionos_mail_acceptance 测试库，供用户验收。原恢复库保留不动。用户验收结束后，去掉邮件验收覆盖配置并重建backend，恢复原演练库。真实旧账号的认领、公网切换和真实模型仍未验收。

后端重启后，密码登录、历史读取、口令重放拒绝及隔离检查全部再次通过。监控 /health 返回 ok=true、stale=false、consecutiveFailures=0。原备份89账号、8543会话、1378消息按备份时点筛选后，三个完整内容SHA-256均与原baseline一致。原库另有18:09创建的密码账号，以及前次合成账号，均保留；不能仅排除前次合成账号后直接与89账号基线比较。NewRICH两个PID保持822792/822795，均active；可用内存2011MiB、swap使用0。

用户验收入口：http://127.0.0.1:13000/login。测试邮箱为用户指定邮箱；随机测试密码见本机 ~/.config/questionos/rehearsal/mail-credentials.json（0600）。此密码只适用于独立测试库，不是用户原账号密码。

## 浏览器验收发现与修复：登录跳转状态

用户发现密码登录跳回首页仍显示未登录。浏览器复现确认：旧页面只写localStorage，未更新AuthProvider；刷新后登录态才恢复。已在login/register成功分支调用既有setUserFromLogin，与Google登录和recover保持一致。此前API验收不等于浏览器完整通过，此处补齐该遗漏。

验证：npm run test:contracts、npm run typecheck、npm run build均通过。Docker复用此前frontend镜像的依赖和运行时重新构建.next（Dockerfile.cached在私有构建目录），产物questionos-frontend:rehearsal-auth-state-20261008，config SHA256 0b1e8198cf44423fd4baab1e460c8fcbe2b8570a88c538530a9aaf1a30065309。传输归档校验一致，仅重建演练frontend。前一镜像保留；原配置保留服务器rehearsal.before-auth-state.env。

实际浏览器退出→密码登录→首页即显示账号/历史（未刷新）→历史列表显示合成会话→返回首页仍登录，通过。注册共用状态修复已构建验证，未新增浏览器注册账号。截图本机 ~/.config/questionos/rehearsal/auth-state-fixed.png。当前仍为独立测试库，未修改真实账号、DNS或Railway。

用户已明确反馈“登录和历史记录没问题了”，该独立测试库的登录/历史浏览器验收通过。真实模型及生产切换仍独立验收。
