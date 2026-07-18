# TASK-20260718：自研采集内核设计与迁移

## 状态

实现阶段：first-party 协议、ContentEnvelope、生产控制面和默认关闭策略已落地；自动化测试和生产 smoke 通过后直接作为主路径，不设置 14 天影子比较门禁。

## 目标

减少 Firecrawl 在信息采集链路中的依赖，由 FinBot 自己实现请求、代理路由、响应安全、通用内容分块、AI 语义清洗与校验、规范化、去重和观测；Firecrawl 仅作为来源级可选渠道。

## 当前已完成

- 已核对现有 `FirecrawlSourceCollector`、`RssSourceCollector`、`RoutingSourceCollectionGateway`、`JsoupEvidenceNormalizer` 和 `IngestionApplicationService`。
- 已确认 `raw_evidence`、`normalized_document`、`source_collection_run` 可以作为迁移后的不可变研究事实存储。
- 已形成需求文档：[`2026-07-18-first-party-crawling-architecture.md`](../../docs/requirements/2026-07-18-first-party-crawling-architecture.md)。
- 已形成架构决策：[`022-first-party-crawling-core.md`](../../docs/decisions/022-first-party-crawling-core.md)。
- 已完成 `HTML_DOCUMENT`、`SEARCH_DISCOVERY`、`JSON_API`、`SITEMAP`、`WEB_CRAWL` 路由、统一 `CrawlerTransport`、`ContentEnvelope`/稳定 block ID、`normalized_document.content_blocks`、多 Agent 合法 block 引用门禁及 UI 引用展示、全局/单主机背压、RSS 请求级代理与响应上限、运行时 SSRF 拒绝、Firecrawl fail-closed 和默认关闭、Liquibase 035-041、SearXNG/Brave 兼容 JSON 适配器和 Java/Web/OpenAPI/Proxy 单测。

## 下一阶段实现顺序

1. 定义并接入 `CollectorProtocol`、`FetchPlan`、`FetchAttempt`、`ContentEnvelope`、`AiCleaningPolicy` 和统一错误码。
2. 把请求安全、代理、重定向、限速、响应上限和重试收敛到 `CrawlerTransport`。
3. 实现 HTML、RSS/Atom、JSON API、Sitemap first-party adapter，并补齐 fixture、WireMock 和 SSRF 测试。
4. 新增 Liquibase changeset 与控制面 API/UI，支持 AI 清洗策略、可选 ExtractionHint、限额、测活预览和健康状态。
5. 对现有来源执行真实在线测活和生产采集 smoke，通过后按来源直接切换 primary collector。
6. 保留 Firecrawl 独立渠道及三个显式操作模式；默认关闭，first-party 失败不得隐式切换，不以日历等待作为条件。

## 非目标

- 本任务第一阶段不实现浏览器渲染。
- 不改变研究压缩、多 Agent 辩论、量化预测和模拟交易流程。
- Firecrawl 是显式可选渠道；默认关闭且只能通过来源配置调用，不参与 first-party 失败后的隐式跳转。

## 验收

- 需求文档中的功能、可靠性、安全和可维护性门禁全部有自动化测试或真实运行证据。
- 生产信息源按来源可回滚，且迁移不会重复写入历史证据。
- `git diff` 不包含与采集迁移无关的前端、交易或部署资源变更。
