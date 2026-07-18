# TASK-20260718：自研采集内核设计与迁移

## 状态

实现阶段：first-party 协议、ContentEnvelope、生产控制面和目录 v1/v2/v3 已落地，国内外新闻、内部 SearXNG 与 AI Web Search 已生产发布；047 已将来源级引擎配置编译为 SearXNG 官方支持的 `!shortcut` 查询语法，048 根据真实 smoke 增加显式可用引擎冗余；不设置 14 天影子比较门禁。

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
- 生产真实 smoke 发现新闻专用引擎与 Baidu/Sogou 会同时受解析错误、CAPTCHA 或限流影响；048 为国际新闻组显式加入 `bi,ddg`，为国内组显式加入 `bi,ddg` 且保留国内引擎，候选配置分别返回 13 和 10 条公网结果。

## 下一阶段实现顺序

1. 在 CI PostgreSQL 服务中执行 Liquibase 048 完整升级门禁，断言 6 个 SearXNG 来源映射正确且遗留 `engines` 为 0。
2. 发布到 K8S/ArgoCD，验证 048 已登记、Backend 新版本 Ready、NetworkPolicy 与代理健康。
3. 使用最终数据库 shortcut 配置重复国内、国际和新闻真实 JSON 搜索 smoke；GDELT/SEC 或搜索引擎的 429/403 必须以健康状态和 attempt 记录呈现。
4. 保留 Firecrawl 独立渠道及三个显式操作模式；默认关闭，任何渠道失败不得隐式切换，不以日历等待作为条件。

## 非目标

- 本任务第一阶段不实现浏览器渲染。
- 不改变研究压缩、多 Agent 辩论、量化预测和模拟交易流程。
- Firecrawl 是显式可选渠道；默认关闭且只能通过来源配置调用，不参与 first-party 失败后的隐式跳转。

## 验收

- 需求文档中的功能、可靠性、安全和可维护性门禁全部有自动化测试或真实运行证据。
- 生产信息源按来源可回滚，且迁移不会重复写入历史证据。
- `git diff` 不包含与采集迁移无关的前端、交易或部署资源变更。
