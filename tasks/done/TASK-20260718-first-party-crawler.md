# TASK-20260718：自研采集内核设计与迁移

## 状态

已完成并归档：first-party 协议、ContentEnvelope、生产控制面和目录 v1/v2/v3/v4 已落地，国内外新闻、内部/公共 SearXNG 与 AI Web Search 已生产发布；047-049、长 source ID 异步采集与信息源在线测试异步化均已完成 CI/GitOps 和公网验收。后续 C1/C2/C3 challenge 能力由 ADR 026 与 requirement 37 独立跟踪，不回写本任务的第一阶段范围。

## 目标

减少对第三方采集编排的依赖，由 FinBot 自己实现请求、代理路由、响应安全、通用内容分块、AI 语义清洗与校验、规范化、去重和观测；Firecrawl 与 first-party 是独立渠道，Firecrawl 仅作为来源级显式可选渠道。

## 当前已完成

- 已核对现有 `FirecrawlSourceCollector`、`RssSourceCollector`、`RoutingSourceCollectionGateway`、`JsoupEvidenceNormalizer` 和 `IngestionApplicationService`。
- 已确认 `raw_evidence`、`normalized_document`、`source_collection_run` 可以作为迁移后的不可变研究事实存储；默认目录通过 `information_source_catalog_manifest` 固化版本和哈希。
- 已形成需求文档：[`2026-07-18-first-party-crawling-architecture.md`](../../docs/requirements/2026-07-18-first-party-crawling-architecture.md)。
- 已形成架构决策：[`022-first-party-crawling-core.md`](../../docs/decisions/022-first-party-crawling-core.md)。
- 已完成 `HTML_DOCUMENT`、`SEARCH_DISCOVERY`、`JSON_API`、`SITEMAP`、`WEB_CRAWL` 路由、统一 `CrawlerTransport`、`SearchDiscoveryProvider`、`ContentEnvelope`/稳定 block ID、`normalized_document.content_blocks`、多 Agent 合法 block 引用门禁及 UI 引用展示、全局/单主机背压、RSS 请求级代理与响应上限、运行时 SSRF 拒绝、受限重定向和跨 origin 凭据剥离、Firecrawl fail-closed 和默认关闭、Liquibase 035-043、SearXNG/Brave 兼容 JSON 适配器和 Java/Web/OpenAPI/Proxy 单测。
- 已完成免费结构化目录 v2：SEC/GDELT/World Bank/BLS/CFTC/FRED/EIA 策略、Bybit JSON、White House RSS；FRED/EIA 通用 Key 绑定和请求时注入；GDELT/SEC `WEB_CRAWL` 出口、CFTC 降序查询；manifest append-only 历史和可回滚迁移；来源运行健康、采集恢复、Firecrawl 独立预算/熔断、来源/主机并发和礼貌延迟；对应 Java/Web/OpenAPI 测试及在线协议核验。
- 已完成目录 v3 源码：61 个来源；国际综合新闻 12 项、国内直接新闻 9 项、国内定向发现 2 项、交易所公告 8 项；CISA/NVD/arXiv/WHO/FDA/USDA/NOAA/USGS/NASA 等垂直来源；单副本 SearXNG 多引擎配置、内部 allowlist、强制代理和 NetworkPolicy；`AI_WEB_SEARCH` Provider/Model binding、调用审计、结构化引用门禁及 UI 编辑。
- 已完成 SearXNG 引擎路由修正：校验最多 16 个来源级 engine shortcut、拒绝会被忽略的遗留 `engines=`、编译 `!shortcut` 查询前缀、保留原业务 query 和 `search_engine_shortcuts` 证据元数据；047 使用精确旧 endpoint 作为更新条件，避免覆盖管理员修改。
- 生产真实 smoke 发现新闻专用引擎与 Baidu/Sogou 会同时受解析错误、CAPTCHA 或限流影响；048 为国际新闻组显式加入 `bi,ddg`，为国内组显式加入 `bi,ddg` 且保留国内引擎，最终分别返回 16 和 10 条公网结果。
- 生产控制面 smoke 发现长 source ID 被错误拼入最长 40 字符的 idempotency scope，导致异步 `/collect` 返回 400；已改为固定 `manual-ingestion` scope，并将 source ID 纳入被 SHA-256 的 client key，生产异步任务均首次尝试 `COMPLETED`。
- 生产公网同步 `/test` 在 SearXNG 长查询期间被 Cloudflare 504 截断，而后端仍继续完成采集；已将在线测试改为独立 `source-test` 幂等域的持久化 `INGESTION` 任务，控制面立即返回 `202`，Web 短轮询任务并按 source/query/任务创建时间关联采集运行，原结构化统计保持不变。
- 最终生产 smoke：公网 `202` 首响应 964 ms，任务 `task_00000mrqnqo5w_0aeec89fc6111703dd0f` 首次尝试 `COMPLETED`；对应采集运行 `collection_00000mrqnqqdn_9f5af9015b148c9f4650` 获取 20 条、新增 7 条、去重 13 条且无错误码。ArgoCD revision `a68f4b7d802d361b095f398a837926c2a1230898` 为 `Synced/Healthy/Succeeded`。
- 公共实例池生产验收：提交 `dcaccb2` 的 CI run `29669029088` 全部通过，GitOps revision `1ebbf7ea8649d7e3f26242547c040e2063bb0312` 为 `Synced/Healthy/Succeeded`，backend/quant/web 均为 `sha-dcaccb27aab04a2926eba4c4face4676df09f369` 单副本且零重启；Liquibase 049 为 `EXECUTED`，catalog v4 为 62 条来源。公网真实测试任务 `task_00000mrr5j7aa_1a0de31f03b332d99404` 首次尝试 `COMPLETED`；首次有效公共池运行 `collection_00000mrr5ao0m_8c87aeec2267f0ca657e` 在三实例尝试后明确 `BLOCKED / PUBLIC_SEARXNG_POOL_EXHAUSTED / HTTP 403`，后续运行按设计进入一小时全池冷却，未发生直连或隐式渠道切换。

## 下一阶段实现顺序

1. 持续观测各 engine 的有效结果率、CAPTCHA、解析错误和限流，不以 `/healthz` 代替结果健康。
2. Provider 工具协议测活后再按需启用 Grok/Gemini AI Web Search 默认来源，继续保持独立 token 与引用审计。
3. 扩充行业和地区来源时优先官方 API/RSS；新增搜索 engine 必须先通过代理出口真实结果 smoke。
4. 保留 Firecrawl 独立渠道及三个显式操作模式；默认关闭，任何渠道失败不得隐式切换，不以日历等待作为条件。
5. 公共 SearXNG 只把明确 JSON 响应作为成功；后续显式 Browser Profile 不得改变来源身份、冷却和独立错误语义。

## 非目标

- 本任务第一阶段没有实现浏览器渲染；后续独立 Browser Worker 已上线，但生产来源尚未启用。
- 不改变研究压缩、多 Agent 辩论、量化预测和模拟交易流程。
- Firecrawl 是显式可选渠道；默认关闭且只能通过来源配置调用，不参与 first-party 失败后的隐式跳转。

## 验收

- 需求文档中的功能、可靠性、安全和可维护性门禁全部有自动化测试或真实运行证据。
- 生产信息源按来源可回滚，且迁移不会重复写入历史证据。
- `git diff` 不包含与采集迁移无关的前端、交易或部署资源变更。
