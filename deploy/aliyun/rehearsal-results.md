# Pre-RICH 隔离演练结果 — 2026-10-08

> 历史阶段材料。当前部署与验收以[正式切换执行单](cutover-plan-20261009.md)为准；本文的“当前/待验收”均指记录当时。
状态：四服务已在 Pre-RICH 120.27.147.161 运行，Compose 项目 questionos-rehearsal，目录 /opt/questionos-rehearsal。用户已明确确认本次范围；没有正式切换、修改网站 DNS/Caddy 或停止 Railway。

## 产物与隔离

- 三个应用镜像使用 rehearsal-fa0a54f49911，导入前验证镜像包 SHA-256 与本机 manifest 完全一致。原始 manifest 和归档保存于服务器 private/，未推送公共仓库。
- PostgreSQL 使用已固定的 18.6 linux/amd64 摘要，先核实独立卷不存在、创建后 public 业务表数量为0，再以单事务恢复。
- 备份传输 SHA-256：c286f265a28e107cebea94dd2f279c5f26901d6743d5a7f89204c33451505d3a，与本机原文件一致。
- 私有目录0700，rehearsal.env与备份0600。演练JWT/sandbox和数据库密码独立生成，未使用线上JWT、模型或SMTP凭据。
- app/data网络internal=true；为让Docker绑定回环入口，前端和监控另接access桥接网络。后端/数据库仍只在internal网络；邮件主机为空，模型端点为127.0.0.1:9且无真实凭据。
- 前端127.0.0.1:13000、监控127.0.0.1:13001；数据库和后端无宿主端口。初次将所有网络设为internal导致端口未实际绑定，调整后已通过ss和docker inspect核对。

## 数据与应用验证

- 恢复账号89、会话8543、消息1378，规范化邮箱冲突0。
- 实际Flyway成功验证V1–V6并升级V7。升级启动后原三业务表逐行排序内容SHA-256均未变化。
- 后端加载8517个可见会话，其余26个是原有软删除记录；不是恢复缺失。
- 通过SSH隧道：首页/recover/login为200，未登录接口401，邮箱认领请求503（SMTP明确禁用）。
- 使用仅演练环境有效的短时签名验证恢复数据读取，未改真实账号密码：选定旧账号的2个可见会话数量一致，其中一会话的50条消息ID/角色/正文/轮次与库中逐条匹配。此步骤不等同于真实用户登录或邮件认领。
- 注册专用合成账号 migration-rehearsal-20261008@example.test，创建1条标记会话；密码登录与本账号会话读取通过，读取旧用户历史返回404。
- 四服务重启后，上述密码登录/合成会话持久化/历史读取/跨账号拒绝再次通过。排除专用合成记录后，原三业务表内容指纹仍完全一致。
- 专用合成账号和会话保留供后续验收，没有清场删除。私有测试凭据及实体ID在本机 ~/.config/questionos/rehearsal/synthetic.json（0600），不写入Git或聊天。
- 监控/health为ok=true、stale=false、consecutiveFailures=0；首页、运行时client ID、proxy401、后端health、prometheus五项通过。自动合成写入和LLM项按配置跳过。

## 资源与现有服务

- 启动前可用内存2479MiB；重启后4次间隔5秒采样为2006/2019/2025/2032MiB，swap使用0。仅启动/轻量验收负载，不是并发容量结论。
- 四容器OOMKilled=false、RestartCount=0。
- NewRICH real/synthetic的MainPID仍分别为822792/822795，systemd均active；Caddy配置SHA-256仍为55381f17e169b4099d7a2673efd9ca6a25e357d01fde7e3e2e9461af7fd96249。
- NewRICH没有已知/health端点（返回404）；这里只验证进程连续性、监听状态与配置未变化，未执行其业务交易验收。

## 后续边界

SSH隧道当前将本机13000/13001转发到服务器回环地址。隧道断开时网站不对本机可见，服务器演练容器仍运行。

公网切换前仍需处理依赖安全告警、真实模型与容量验收、Java真实认领投递/用户路径、定时备份和恢复、最终停写导出与切换方案。邮件DNS已完成，但本次没有网站DNS/Caddy变更；依然不是正式迁移完成。

## 邮件验收后续

真实邮件认领已在独立 questionos_mail_acceptance 测试库通过，详见 mail-acceptance.md。当前回环演练页面暂连此测试库；上文 SMTP 禁用和恢复库连接描述属于此前阶段。原恢复库保留，未修改真实用户密码。
