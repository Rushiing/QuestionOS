# Release evidence

每次生产合并后的证据放在 PR 评论或 GitHub Release，不长期提交包含易漂移状态的大型 JSON。

最小记录：

- merge commit；
- 风险等级和用户授权；
- 受影响 Railway 服务；
- deployment ID / status / running commit；
- migration 与 Postgres 连通证据；
- frontend、backend、Postgres、smoke-monitor 分服务结果；
- 真实用户路径结果或明确未执行项；
- 回滚 deployment / checklist；
- truth-sync 与临时资源清理结果。

使用 `scripts/questionos-release-evidence.mjs` 生成机器可读证据，再把不含 secret、生产数据和易过期大段日志的摘要贴入 PR/Release。
