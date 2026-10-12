# 正式切换前验证 — 2026-10-08

> 历史阶段材料。当前部署与验收以[正式切换执行单](cutover-plan-20261009.md)为准；本文的“当前/待验收”均指记录当时。
> 历史阶段记录：正式切换及后续Railway退出已完成，现役状态见[正式状态记录](cutover-plan-20261009.md)。以下“待批准/未完成”等描述仅指记录当时。

> 2026-10-09后续修复及最新验收见[回答质量复验](quality-followup-20261009.md)。以下保留2026-10-08阶段记录。

## 模型连通性（真实，仅一次）

通过Railway实际backend环境核对：百炼 https://dashscope.aliyuncs.com/compatible-mode/v1，模型deepseek-v4-flash，thinking=false。凭据由CLI捕获并通过SSH写入Pre-RICH私有0600配置，无聊天或Git输出。

用户批准后，从Pre-RICH宿主机发起1次POST /chat/completions：仅发送“请只回复 OK”，max_tokens=32、stream=false、enable_thinking=false，不重试。结果HTTP200，回复OK，耗时0.79秒，输入8/output1/total9 tokens。结果保存在服务器private/model-probe-result.json及本机私有目录。

该探针只证明宿主机到模型可用。随后用户扩大授权，已完成真实 Java 对话验收，详见 [模型验收记录](model-acceptance-results.md)。

## 前端依赖候选（尚未部署）

Next14.2.35升级到15.5.27，保留现有React18依赖范围；适配API代理异步params，Axios拦截器返回body类型显式声明，TypeScript target为ES2017。Next内部PostCSS通过定向override固定8.5.29，不升级到Next16。npm缓存锁记录曾保留旧嵌套PostCSS，已同步其lock记录并用全新npm ci确认实际安装为8.5.29。

官方npm registry的运行依赖审计：原13项（1critical/7high/2moderate/3low）→3low、0moderate/0high/0critical。剩余3项为markmap-lib→@vscode/markdown-it-katex→katex同一依赖链，不能说“零漏洞”。上游KaTeX修复需要跨0.x次版本，不为清零告警盲目降级markmap-lib到npm建议的0.8.1。

验证：npm ci、npm run test:contracts、npm run typecheck、npm run build通过。临时本机13300运行Next15生产构建，通过SSH临时隧道直接连接隔离Java测试库；实际浏览器密码登录跳转立即显示账号，历史列表正常。该路径不等于新Linux镜像上线验收，服务器13000仍使用已验收的rehearsal-auth-state-20261008镜像。

来源：
- https://nextjs.org/docs/app/guides/upgrading/version-15
- https://github.com/vercel/next.js/security/advisories/GHSA-2xp9-vwfh-vxw4
- https://github.com/KaTeX/KaTeX/security/advisories/GHSA-238p-pmpm-9mq7

## 真实应用验收

用户已授权遍历模型调用，并要求修复实测回答质量问题。首轮覆盖12个虚构场景、21回合、36次模型请求：校准、沙盘分诊/追问/语义点火、角色轮转与整合、共识评估、问题消解、非流式与自动标题。结果和质量修复见 [模型验收记录](model-acceptance-results.md)。

尚待：并发容量、Linux前端依赖候选镜像、定时异地备份、正式域名入口、停写最终导出与切换批准。Railway保持运行。

### POST错误响应回归

本机Node25.6.0下，转发ArrayBuffer/Uint8Array的fetch在401路径报detached ArrayBuffer，前端表现为502；同一Java请求直接返回401。用独立原生fetch对照复现：ArrayBuffer和Uint8Array失败，Blob和字符串返回401。代理请求体改为不可变Blob，保持原二进制内容和Content-Type，避免为修复JSON而破坏文件上传。新增scripts/check-auth-proxy.mjs覆盖未登录401、错误密码401、非法输入400。此问题在旧服务器Node20前端未出现，不能描述为生产已有故障。

最终复验：新代理的未登录401、错误密码401、非法参数400通过；模拟账号密码登录、历史消息读取、旧口令重放拒绝、跨账号404全部通过。未再次发邮件，未再次认领，也没有追加模型调用。
