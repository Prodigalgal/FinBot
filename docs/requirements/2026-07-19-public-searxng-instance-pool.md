# S3：公共 SearXNG 实例池渠道

> 状态：核心渠道和 049 migration 已实现。请求身份/challenge 控制已由 ADR 026 扩展；生产当前仍使用透明默认 Profile，C2/C3 未启用。

## 目标

新增独立的 `searxng_public_pool` 搜索发现渠道，从 `https://searx.space/data/instances.json` 获取公共实例目录，经 `WEB_CRAWL` 代理筛选并调用明确开放 JSON Search API 的实例。该渠道补充自建 `searxng_internal`，不替换它，也不作为其他渠道失败后的隐式 fallback。

## 范围

- 沿用 `SEARCH_DISCOVERY`、`SearchDiscoveryProvider`、`CrawlerTransport` 和 `InformationSource`，不增加新的 SourceMode。
- 目录和搜索请求都固定使用 `OutboundRoute.WEB_CRAWL`、`requireProxy=true`，无直连回退。
- 目录缓存 6 小时，单次最多接受 256 条候选；目录响应上限 2 MiB。
- 候选实例必须满足：HTTPS、无 user-info/query/fragment、`main=true`、`network_type=normal`、`analytics=false`、HTTP 200、HTTP/TLS 评级为 A 或 A+、SearXNG generator、搜索成功率不低于 95%、月可用率不低于 99%、搜索中位延迟不高于 3 秒，并至少有一个 HTTPS 可达的 A 或 AAAA 地址。
- 搜索时按目录成功率、可用率、延迟排序并轮转起点；单次最多顺序尝试 3 个不同实例，每个实例只发起一次请求。
- 仅接受 `application/json` 且根对象包含 `results` 数组的响应；HTML challenge、格式未开放和畸形 JSON 均视为实例不可用。
- 解析并保留 `unresponsive_engines`、实例主机、目录时间、地址族、代理路由、实例尝试数和抓取尝试数；不保存代理 URL、凭据或目录中的非必要运营者数据。

## 安全与访问规则

1. 默认 `header_default` 使用明确的 `FinBot/2.0` User-Agent 和联系信息，不生成虚假转发 IP；管理员可以显式绑定其他 Browser Profile，但不得隐式切换。
2. C1 对 Anubis、CAPTCHA、JavaScript challenge、WAF 和 limiter 始终分类。默认 Profile 不执行求解；显式 C2/C3 失败仍进入公共池冷却并保留原分类。
3. `CrawlerTransport` 在每次请求和重定向前执行 DNS/地址安全检查，拒绝 loopback、link-local、private、ULA 和保留地址。
4. searx.space 目录入口固定为精确 HTTPS URL；管理员不能用该 provider 将目录改为任意站点。
5. 公共实例可观察查询内容，因此来源只用于公开市场、行业和新闻研究，不发送 Secret、账户数据、未发布交易指令或用户凭据。
6. 公共池固定 `WEB_CRAWL requireProxy=true`。Browser Worker 在接入同一代理且验证 fail-closed 前，不允许为该来源启用 `BROWSER_WORKER`。

## 失败与冷却

- 401/403/418：实例冷却 24 小时。
- 429：实例冷却 1 小时；不在同一实例快速重试。
- 5xx、超时和网络失败：实例冷却 15 分钟。
- HTTP 200 但非 JSON、缺少 `results` 或挑战页：实例冷却 24 小时。
- 一次搜索的候选全部失败后，公共池全局冷却 1 小时；期间不继续枚举其余实例。
- 目录不可用、无合格候选、全部候选冷却或三实例耗尽时，来源返回稳定的 `PUBLIC_SEARXNG_*` 错误并标记 `BLOCKED`；其他来源继续执行。

## 默认来源

- 追加 default source catalog v4，不修改 v1-v3。
- 新来源 `source_searxng_public_pool` 使用 T4、低信任权重、P3、6 小时建议轮询周期和最多 20 条结果。
- 渠道默认启用，但在没有合规 JSON 实例时只产生可观测的 `BLOCKED` 运行，不伪装成成功，也不切换到直连、自建 SearXNG、AI Web Search 或 Firecrawl。

## 验收标准

1. 单元测试覆盖目录筛选、IPv4/IPv6 元数据、轮转、代理必需、JSON 成功、HTML challenge、429 冷却、候选耗尽、恶意 URL 和响应上限。
2. PostgreSQL 集成测试验证 v4 manifest、来源字段、历史 manifest 保留和幂等迁移。
3. 本地 Java 全量测试、OpenAPI、Web、Quant、Kustomize 和 Secret scan 通过。
4. 生产通过代理读取 searx.space 目录；只有真实 JSON 结果才算成功。若当前无合格实例，验收结果必须明确记录 `BLOCKED`，不得把 challenge HTML、Browser Worker 部分成功或其他渠道结果伪装成本渠道成功。
