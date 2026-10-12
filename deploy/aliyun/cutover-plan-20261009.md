# QuestionOS 正式切换执行单 · 2026-10-09

状态（2026-10-09 收尾核对）：正式切换已完成，用户已确认旧账号找回、刷新登录态与历史记录通过；创建会话403已修复并完成服务端正式Origin链路复验。现役后端为 `questionos-backend:production-cors-20261009`。待核验2026-10-10凌晨自动备份及观察结果，之后按具体清单批准退出Railway。

原 Railway backend/frontend/smoke-monitor 已停用，2026-10-09 进一步断开三项 GitHub 自动发布触发器并读回为0；旧Postgres与volume保留，Hobby订阅未取消。当前账期结束于2026-10-24 15:23:24（北京时间），用户退出期限仍为20日前。

下文保留按阶段记录；其中“未切换”“待用户验收”“演练库备份”等只描述当时状态，不能覆盖本节。上线代码存在本地未提交改动，源码集成仍为pending；保留迁移工作树、发布包与验收证据。

## 切换前历史检查

- Pre-RICH 独立演练：Next 15.5.27 Linux amd64 前端已部署，镜像 `questionos-frontend:rehearsal-next15-20261009`，ID `sha256:ca6a4ecd5589b565806e3f0afba3892b2d9bc5f1cc4fad86ef794a4338801f33`。传输包 SHA256 `59052fda0ed125dcc42a57f0a80c0d636d4281cd92979e53665d92bc95a391ec`。旧镜像与 `rehearsal.before-next15.env` 保留。
- 400/401错误代理、密码登录、12场景59条历史回读及浏览器登录态/报告恢复通过；4并发24次只读请求全部200，最大0.363秒。这不是模型并发容量证明。
- 新前端真实CALIBRATION一轮通过，47个agent_delta、一次turn_done、无agent_error，约4.85秒；Last-Event-ID尾部3事件连续重放通过。只检查迁移链路，不进行新一轮效果调优。
- OSS账户 `questionos-backup`、策略 `QuestionOSBackupPrefixAccess` 已创建并授权。只可读写现有杭州私有桶 `rushrich-backup-33407-20260713/questionos/`；实测越界列举 `rushrich/` 被拒绝。没有删除权限，没有修改原RICH策略。
- OSS真实上传→下载字节比对→独立空库恢复成功。模拟库6表逐行一致：2账号、41会话、438消息、7条Flyway记录、1条认领、1条预算记录。验证库 `questionos_oss_verify_20261009` 保留。此备份不代表Railway最终生产数据。
- 每天北京时间04:15（最多随机延迟120秒）备份timer已启用；手动通过systemd执行Result=success、ExecMainStatus=0。当前仅备份 `questionos_mail_acceptance`，正式切换必须改为生产容器/库并立即复验。失败进入journal及last-failure，last-success仅在OSS回读通过后更新；外部主动告警尚未接入。
- 现有桶与服务器同在杭州，属于离机备份，不是跨地域容灾；未新增Bucket、未添加自动删除规则。
- 服务器可用内存约1938MiB、swap使用0；前端healthy、OOMKilled=false、RestartCount=0；NewRICH real/synthetic与Caddy均active。
- Railway只读复查：四服务仍有SUCCESS活动部署；应用源SHA仍为`ecadf090a2d683fb872ac463acc819de06d06530`。当前89账号、8543会话、1378消息、重复归一邮箱组0。
- `/opt/questionos-prod` 已仅暂存正式Compose、runtime覆盖和0600私有环境文件，`config --quiet`通过，未启动生产容器/卷。保留Railway原JWT、sandbox token、Google client ID及模型配置；目标数据库独立密码，沿用已验收SMTP设置。
- 用户接受入口 `questionos.daydreamer.world` 和后续 `cybermomo.daydreamer.world`。两个应用子域名目前无DNS记录；个人网站及主域名整体规划仍最后处理。新增QuestionOS站点的完整Caddy候选已validate，未替换/重载现有配置。

## 已批准执行范围

目标：Pre-RICH `120.27.147.161`，Compose `questionos-prod`，全新卷 `questionos-prod_postgres_data`；入口 `https://questionos.daydreamer.world`。

1. 确认旧站无在途模型回合，冻结QuestionOS旧入口和backend写入；保留Postgres、数据卷、旧部署信息和代码，其他Railway项目不动。预计短暂维护窗口，实际以恢复验收耗时为准，不承诺零停机。
2. 停写后，通过官方Railway SSH只读pg_dump重新导出，私有保存与哈希核对；不得使用演练库或昨日备份替代最终源数据。
3. 在全新生产卷启动Postgres，确认public表为空，再单事务恢复；精确比较源/目标账号、会话、消息及全部原表，核对Flyway后启动backend执行V7，原始数据不得改变。
4. 停止演练frontend/monitor释放13000/13001（保留演练库和镜像），启动正式四服务；数据库和backend不公开端口。
5. 创建 `questionos.daydreamer.world` A记录→`120.27.147.161`，TTL600；备份当前Caddy配置，再合并已验证的独立站点并reload，获取HTTPS。不更换NS，不改其他域名/邮件记录或NewRICH站点。
6. 核对公开入口、登录、原历史、邮箱找回、流式回合与重连；生产验证限指定用户或明确标记的测试账号，不能对陌生用户历史调用模型。验证后monitor检查公网origin。
7. 备份服务切至 `questionos-prod-postgres-1` / `questionos`，立即上传、下载及隔离恢复验证；保留旧库。观察至少一天，期间旧站不可同时写入。
8. 正式稳定后进入CyberMOMO迁移。两个应用均完成并验收后再执行Railway退订与资源清理；本执行单不批准删除数据库/卷，也不批准退出整个Railway账户。

## 回退边界

- 新站尚无新增写入：停止正式应用、恢复原入口/旧部署，保留目标数据供排查。
- 新站已有新增写入：先停止新写入并保存新库，评估数据回迁，不能直接启用旧库造成分叉。
- 新站HTTPS、账号历史、原表校验、备份任一关键检查失败，不开放正式写入。容量下限沿用可用内存768MiB、无OOM/明显换页且不影响NewRICH。

私有证据：`~/.config/questionos/rehearsal/next15-linux-*`；`/opt/questionos-backup/restore-verification-20261009.json`、`last-success`、systemd日志。服务端凭据仅存私有配置，临时本地传输文件已移除。

## 切换执行进度（2026-10-09，北京时间约12:33）

- Railway frontend/backend 已停止，回读活动部署均为空；Postgres/数据卷仍保留。旧站目前不可用，未宣称迁移完成。
- 停写后的最终归档503402字节，SHA256 `8b1e78aaa83d4b08feed18de1d9663839dacb496eb1157d285c3e9721562d7d6`，源/目标归档一致。新生产空库单事务恢复成功，原4表逐行内容指纹一致。
- 正式 backend 执行V7成功；89账号、8543会话、1378消息指纹仍与源库完全一致。四服务已运行，frontend/Postgres/monitor healthy，monitor `/ready` 返回ready=true。尚未创建正式测试账号或模型回合。
- 备份timer已改为 `questionos-prod-postgres-1` / `questionos`。04:31:16Z完成OSS上传、下载字节核对，键前缀 `questionos/questionos/20261009T043114Z-60ae1895-cd2f-4f06-950e-09506a319caf/`。下载件已恢复至独立空库 `questionos_prod_oss_verify_20261009`，全部6表行数及指纹与生产库一致。
- 新增DNS首次提交返回PostonlyOrTokenError。刷新后页面变为国际站账号，原域名记录不可见；没有确认任何DNS写入成功。已请用户恢复拥有域名的拥有该域名的国内账号。Caddy未修改，公网HTTPS、正式登录/模型/SSE验证尚未执行。
- 私有证据：本机 `~/.config/questionos/cutover-20261009/`；服务器 `/opt/questionos-prod/restore-final-verification.json`、`post-v7-original-fingerprints.json`、`/opt/questionos-backup/production-restore-verification-20261009.json`。

## 公网切换结果（2026-10-09，北京时间12:43）

- 国内账号恢复后，DNS于12:37:58保存：`questionos.daydreamer.world A 120.27.147.161`，TTL600、启用。控制台回读与服务器权威DNS查询一致。其他记录未修改。
- Caddy原配置备份 `/etc/caddy/Caddyfile.before-questionos-20261009T043740Z`，追加独立QuestionOS站点，通过validate并reload。12:39:28取得可信HTTPS证书；HTTP308跳转HTTPS，HTTPS首页200。原NewRICH real/synthetic服务均active。
- 合成账号 `qos-cutover-20261009@example.test`（QOS Cutover Test）注册200，登录、me、合成会话历史读取均200。真实CALIBRATION回合4.67秒，45个agent_delta，1个turn_done，无agent_error。尾部Last-Event-ID=47重放得到48/49/50，验证通过。此账号及会话保留，未删除生产记录。
- 原始89账号、8543会话、1378消息在创建测试记录前已经全量比对一致；新测试会增加正式库计数。旧账号邮件认领已在演练验收，尚未在新域名重新完成真实用户端认领，不冒充该验证完成。
- monitor已改为正式HTTPS origin，容器healthy且ready=true；数据库、前端healthy，backend运行；可用内存约1932MiB、swap使用0。
- 测试后的正式备份再次成功：04:41:29Z，OSS前缀 `questionos/questionos/20261009T044127Z-479f7543-ff7d-40c1-8c85-09a8c36f95a8/`。切换前正式库下载恢复六表一致的证据仍有效，最新备份上传下载字节校验通过。
- Railway frontend/backend/smoke-monitor活动部署均0，Postgres仍1；保留源库/卷与回退资料。未删除资源、未取消Railway订阅。
- 本机浏览器访问被企业网络安全页面接管，等待用户手动处理；不代点放行。最初本机DNS不可用，HTTP测试按权威地址直连并保留完整TLS校验；识别企业拦截后不再使用本机直连绕过，后续登录/历史/SSE验证在服务器正常DNS链路执行。浏览器端公网验收仍待完成。
- 下一步：用户公网浏览器登录/旧账号认领与历史验收，观察至少一天；再继续CyberMOMO迁移。效果优化不阻塞迁移。

## 浏览器写入403修复（2026-10-09 13:40 北京时间）

用户确认公网访问、旧账号找回、刷新登录态及历史记录均通过。随后创建会话403已复现：正式Origin被旧Controller来源列表拒绝，无Origin请求正常。后端统一应用配置中的精确域名到WebFlux映射。`mvn package`通过，54项测试中46通过、8项数据库集成测试跳过，无失败。

上线镜像 `questionos-backend:production-cors-20261009`，JAR SHA256 `e85e01fd6d4d32b4793bc08547e103ddcda79e9e868ed82f43a8f2ae85fd44b0`；回退配置 `/opt/questionos-prod/production.before-cors.env`，原镜像保留。未新增数据库迁移。

服务器经正式HTTPS并携带Origin验证：非法创建参数由403恢复为预期400；陌生Origin仍403；测试账号有效创建201、真实消息200、47个agent_delta、1个turn_done、无agent_error、4.51秒，历史回读通过。证据 `/opt/questionos-prod/releases/cors-20261009/cors-model-smoke.json`。Agent内置浏览器仍遇企业安全拦截，因此不冒充已完成修复后的浏览器点选验收；用户可刷新后直接重试。
