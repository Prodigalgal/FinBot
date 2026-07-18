# ADR 022：自研采集内核，Firecrawl 仅作可选渠道

## 状态

Accepted，2026-07-18。实现按协议逐步落地；first-party 在自动化测试和生产 smoke 通过后直接作为主路径，不设置按日历等待的影子比较门禁。

## 背景

当前 Firecrawl 负责请求、搜索、抓取、正文转换和部分错误处理。生产代理出口受上游 IP 风控影响时，即使 FinBot 的代理网关自身可运行，所有信息源也会同时失败。仓库已经具备请求路由、RSS 解析、Jsoup 清洗、证据哈希去重和 PostgreSQL 持久化能力，没有必要让第三方服务继续承担所有采集职责。

## 决策

1. 建立 first-party crawling core：`CrawlerTransport`、`ContentEnvelopeBuilder`、`AiEvidenceCleaner`、`AiCleaningVerifier`、`EvidenceNormalizer` 和统一 `source_fetch_attempt` 观测；有限 FetchPlan/Planner 继续由当前采集编排复用。
2. 将来源配置从单一“Firecrawl 模式”扩展为“采集协议/渠道 + 发现计划 + AI 清洗策略 + 可选抽取提示”的组合。
3. RSS、JSON API、Sitemap 和静态 HTML 由 first-party collector 作为默认主路径；通用搜索通过独立 `SEARCH_DISCOVERY` 适配器返回摘要；所有请求统一经过代理路由、安全检查（含运行时 DNS）、限速和有界重试。
4. Firecrawl 保留为独立 `FirecrawlSourceCollector` 渠道，仅对来源显式选择的 scrape/search 操作生效，并受熔断、预算和最大调用次数约束。
5. 研究证据表继续是事实来源；first-party 和 Firecrawl 渠道只是在 `raw_evidence.metadata.collector` 中区分，不改下游压缩、辩论和量化契约。
6. 不在第一阶段引入浏览器渲染。需要 JavaScript 的页面由管理员显式选择 Firecrawl 渠道，待有真实样本证明收益后再评估独立 Browser Worker。

## 关键边界

- `CrawlerTransport` 只负责请求和响应安全，不负责抽取业务字段。
- `ContentEnvelopeBuilder` 只做响应安全、通用 DOM/XML/JSON 分块和元数据保留，不判断哪些文本具有研究价值。
- CSS selector 不作为普通来源的必要配置；可选 `ExtractionHint` 只能用于成本优化，不得执行用户脚本。
- AI 清洗必须把网页视为不可信输入，输出事实必须引用原始 block；verifier 不得用模型常识补充原文没有的事实。
- `normalized_document.content_blocks` 保存进入模型的安全视图；原始 HTML 只保存在 `raw_evidence`，不能被 AI 输出覆盖。
- `FirecrawlSourceCollector` 不得被 `RoutingSourceCollectionGateway` 作为默认协议自动选择；必须由来源显式选择 Firecrawl 渠道。
- 代理路由的 fail-closed 语义保持不变：没有健康出口时禁止直连。
- 交易所账户只读同步遵循路由的 `allow_direct`：可选代理连接失败时有限切换直连；Firecrawl/Web Crawl 的 `require_proxy` 路由始终禁止直连。
- `WEB_CRAWL` 与 `FIRECRAWL` 使用不同 Deployment、Service、Secret 键和健康探测；Firecrawl 固定私有四节点池，网页采集不得被 Firecrawl 目标站的 403/429 健康判定连带阻断。

## 取舍

### 选择自研核心

- 优点：减少第三方故障和费用影响；掌握代理轮换、请求安全、证据可追溯和限流；新来源可在 UI 通过 profile 管理。
- 缺点：需要维护站点 profile、反爬差异、解析器和更多测试；对 JS-heavy 页面不能立即替代浏览器型服务。

### 不选择一次性完全删除 Firecrawl

一次性删除会把 JS 渲染和特殊站点迁移风险叠加在同一发布中。保留独立 Firecrawl 渠道可以让 first-party 在通过自动化测试和生产 smoke 后直接接管，同时不改变研究数据的真实性边界。

## 切换与回滚条件

来源切换为 first-party primary 的条件是：

- 协议 fixture、集成测试、SSRF/代理 fail-closed 和响应边界测试通过。
- 该来源完成一次真实在线测活和至少一次生产采集 smoke，结果写入 `source_fetch_attempt`。
- 标题、canonical URL、发布时间和正文可用性满足来源自身的契约；错误分类和重试行为可观测。
- 代理不可用、SSRF 和响应上限测试全部通过。
- 研究工作流回放的输入证据哈希与预期一致时，才允许扩大来源范围。

影子比较可以作为诊断工具单独启用，但不阻塞主路径，也不要求积累 7/14 天样本。

## 回滚

每个来源保留 `primaryCollector` 与渠道配置版本。Firecrawl 是独立采集渠道，提供 scrape/search/search-then-scrape 三种显式操作；first-party 失败不得隐式切换 Firecrawl。生产默认关闭 Firecrawl，管理员可以按来源显式启用，不回滚数据库迁移、不删除 first-party 记录。
