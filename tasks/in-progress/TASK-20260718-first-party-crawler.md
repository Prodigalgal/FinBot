# TASK-20260718：自研采集内核设计与迁移

## 状态

实现阶段：first-party 协议、ContentEnvelope、生产控制面、免费结构化来源目录 v2 和默认关闭策略已落地；源码与 CI 门禁通过，v2 尚待发布到生产；不设置 14 天影子比较门禁。

## 目标

减少对第三方采集编排的依赖，由 FinBot 自己实现请求、代理路由、响应安全、通用内容分块、AI 语义清洗与校验、规范化、去重和观测；Firecrawl 与 first-party 是独立渠道，Firecrawl 仅作为来源级显式可选渠道。

## 当前已完成

- 已核对现有 `FirecrawlSourceCollector`、`RssSourceCollector`、`RoutingSourceCollectionGateway`、`JsoupEvidenceNormalizer` 和 `IngestionApplicationService`。
- 已确认 `raw_evidence`、`normalized_document`、`source_collection_run` 可以作为迁移后的不可变研究事实存储；默认目录通过 `information_source_catalog_manifest` 固化版本和哈希。
- 已形成需求文档：[`2026-07-18-first-party-crawling-architecture.md`](../../docs/requirements/2026-07-18-first-party-crawling-architecture.md)。
- 已形成架构决策：[`022-first-party-crawling-core.md`](../../docs/decisions/022-first-party-crawling-core.md)。
- 已完成 `HTML_DOCUMENT`、`SEARCH_DISCOVERY`、`JSON_API`、`SITEMAP`、`WEB_CRAWL` 路由、统一 `CrawlerTransport`、`SearchDiscoveryProvider`、`ContentEnvelope`/稳定 block ID、`normalized_document.content_blocks`、多 Agent 合法 block 引用门禁及 UI 引用展示、全局/单主机背压、RSS 请求级代理与响应上限、运行时 SSRF 拒绝、受限重定向和跨 origin 凭据剥离、Firecrawl fail-closed 和默认关闭、Liquibase 035-043、SearXNG/Brave 兼容 JSON 适配器和 Java/Web/OpenAPI/Proxy 单测。
- 已完成免费结构化目录 v2：SEC/GDELT/World Bank/BLS/CFTC/FRED/EIA 策略、Bybit JSON、White House RSS；FRED/EIA 通用 Key 绑定和请求时注入；GDELT/SEC `WEB_CRAWL` 出口、CFTC 降序查询；manifest append-only 历史和可回滚迁移；来源运行健康、采集恢复、Firecrawl 独立预算/熔断、来源/主机并发和礼貌延迟；对应 Java/Web/OpenAPI 测试及在线协议核验。

## 下一阶段实现顺序

1. 在 CI PostgreSQL 服务中执行 Liquibase v2 完整升级/回滚验证，覆盖 append-only manifest 和默认目录字段。
2. 为 robots 策略、来源级细粒度阶段计数、健康 API 和真实在线测活补齐剩余边界测试。
3. 发布到 K8S/ArgoCD 后执行 16 个默认来源的真实采集 smoke；GDELT/SEC 的上游 429/403 必须以健康状态和 attempt 记录呈现。
4. 保留 Firecrawl 独立渠道及三个显式操作模式；默认关闭，first-party 失败不得隐式切换，不以日历等待作为条件。

## 非目标

- 本任务第一阶段不实现浏览器渲染。
- 不改变研究压缩、多 Agent 辩论、量化预测和模拟交易流程。
- Firecrawl 是显式可选渠道；默认关闭且只能通过来源配置调用，不参与 first-party 失败后的隐式跳转。

## 验收

- 需求文档中的功能、可靠性、安全和可维护性门禁全部有自动化测试或真实运行证据。
- 生产信息源按来源可回滚，且迁移不会重复写入历史证据。
- `git diff` 不包含与采集迁移无关的前端、交易或部署资源变更。
