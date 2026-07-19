# ADR 025：公共 SearXNG 实例池是独立的受控渠道

## 状态

Accepted，2026-07-19。

## 背景

自建 SearXNG 的部分新闻和地区引擎会遭遇 CAPTCHA、429、页面结构变化和出口信誉限制。searx.space 提供公共实例目录、可用率、延迟、地址族和引擎错误率，但目录评分衡量的是站点可访问性，不保证第三方自动客户端能够使用 JSON API。生产代理实测中，高分实例仍可能返回 limiter 429、Apache 403、Anubis challenge 或 HTML challenge。

## 决策

1. 新增 provider `searxng_public_pool`，继续使用 `SEARCH_DISCOVERY`；它与 `searxng_internal`、GDELT、AI Web Search 和 Firecrawl 并列。
2. searx.space 只提供候选目录，FinBot 经 `WEB_CRAWL` 代理的真实 JSON 响应才是可用性真相。
3. 目录和实例请求均使用 `CrawlerTransport` 的并发、礼貌延迟、响应上限、重定向安全和 fail-closed 代理边界。
4. 采用内存目录缓存、确定性轮转、单实例和全局冷却。当前生产为单副本，不引入仅服务该 best-effort 渠道的数据库健康聚合；重启后从目录和真实响应重建状态。
5. 只接受明确的 JSON API，不解析公共实例 HTML，不执行 JavaScript challenge，不伪装浏览器，不自动解 CAPTCHA。
6. IPv4/IPv6 只记录公共实例入口的可达地址族；它不代表实例访问上游搜索引擎时的出口地址族，也不作为绕过 CAPTCHA 的依据。
7. 公共实例池失败时返回独立错误，不静默调用其他渠道。研究仍由其他已启用来源继续形成证据。

## 取舍

- 公共池增加运营者和出口多样性，但没有 SLA，并向第三方运营者暴露公开查询；因此来源为 T4、低信任、低频并保留 canonical URL 去重。
- 内存冷却在 Pod 重启后丢失，但单副本、每次最多三实例和全局冷却限制了负载；如果未来增加副本或提高频率，再迁移为 PostgreSQL 健康状态。
- 诚实客户端可能被多数公共实例拒绝。这是合规边界，不通过伪装降低门槛；无候选时保持 `BLOCKED` 比产生不可审计结果更可靠。
- 公共实例入口支持 IPv6 不代表当前 K8S CNI 或代理出口具备 IPv6，也不证明其上游搜索出口为 IPv6；生产必须分别验证。

## 回滚

回滚 provider 代码和 catalog v4；049 rollback 只删除 v4 manifest并软删除仍保持默认配置的公共池来源。历史采集、原始证据和规范化文档继续保留。
