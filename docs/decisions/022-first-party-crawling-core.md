# ADR 022：自研采集内核，Firecrawl 仅作可选兜底

## 状态

Accepted，2026-07-18。实现按协议逐步落地，生产切换仍以影子比较结果作为门禁。

## 背景

当前 Firecrawl 负责请求、搜索、抓取、正文转换和部分错误处理。生产代理出口受上游 IP 风控影响时，即使 FinBot 的代理网关自身可运行，所有信息源也会同时失败。仓库已经具备请求路由、RSS 解析、Jsoup 清洗、证据哈希去重和 PostgreSQL 持久化能力，没有必要让第三方服务继续承担所有采集职责。

## 决策

1. 建立 first-party crawling core：`CrawlerTransport`、`ContentEnvelopeBuilder`、`AiEvidenceCleaner`、`AiCleaningVerifier`、`EvidenceNormalizer` 和统一 `source_fetch_attempt` 观测；有限 FetchPlan/Planner 继续由当前采集编排复用。
2. 将来源配置从“Firecrawl 模式”改为“采集协议 + 发现计划 + AI 清洗策略 + 可选抽取提示 + fallback 策略”的组合。
3. RSS、JSON API、Sitemap 和静态 HTML 由 first-party collector 作为默认主路径；通用搜索通过独立 `SEARCH_DISCOVERY` 适配器返回摘要；所有请求统一经过代理路由、安全检查（含运行时 DNS）、限速和有界重试。
4. Firecrawl 保留为独立 `FirecrawlFallbackAdapter`，仅对来源显式允许的 JS-heavy/通用搜索场景生效，并受熔断、预算和最大调用次数约束。
5. 研究证据表继续是事实来源；first-party 和 fallback 只是在 `raw_evidence.metadata.collector` 中区分，不改下游压缩、辩论和量化契约。
6. 不在第一阶段引入浏览器渲染。需要 JavaScript 的页面先使用 fallback，待有真实样本证明收益后再评估独立 Browser Worker。

## 关键边界

- `CrawlerTransport` 只负责请求和响应安全，不负责抽取业务字段。
- `ContentEnvelopeBuilder` 只做响应安全、通用 DOM/XML/JSON 分块和元数据保留，不判断哪些文本具有研究价值。
- CSS selector 不作为普通来源的必要配置；可选 `ExtractionHint` 只能用于成本优化，不得执行用户脚本。
- AI 清洗必须把网页视为不可信输入，输出事实必须引用原始 block；verifier 不得用模型常识补充原文没有的事实。
- `normalized_document.content_blocks` 保存进入模型的安全视图；原始 HTML 只保存在 `raw_evidence`，不能被 AI 输出覆盖。
- `FirecrawlFallbackAdapter` 不得被 `RoutingSourceCollectionGateway` 作为默认协议自动选择；必须由 fallback policy 明确触发。
- 代理路由的 fail-closed 语义保持不变：没有健康出口时禁止直连。
- `WEB_CRAWL` 与 `FIRECRAWL` 使用不同 Deployment、Service、Secret 键和健康探测；Firecrawl 固定私有四节点池，网页采集不得被 Firecrawl 目标站的 403/429 健康判定连带阻断。

## 取舍

### 选择自研核心

- 优点：减少第三方故障和费用影响；掌握代理轮换、请求安全、证据可追溯和限流；新来源可在 UI 通过 profile 管理。
- 缺点：需要维护站点 profile、反爬差异、解析器和更多测试；对 JS-heavy 页面不能立即替代浏览器型服务。

### 不选择一次性完全删除 Firecrawl

一次性删除会把 JS 渲染、通用搜索和静态 HTML 迁移风险叠加在同一发布中。保留显式 fallback 可以让 first-party 逐类接管，同时用影子模式对比正文质量，不改变研究数据的真实性边界。

## 迁移门禁

只有在影子比较达到以下条件后，来源才能切换为 first-party primary：

- 14 天内成功抓取率不低于现有基线的 95%。
- 可用正文率不低于现有基线的 95%，且标题、canonical URL 和发布时间字段没有系统性缺失。
- 证据重复率、错误分类和平均延迟均有可解释差异。
- 代理不可用、SSRF 和响应上限测试全部通过。
- 研究工作流回放的输入证据哈希与预期一致。

## 回滚

每个来源保留 `primaryCollector` 与 `fallbackPolicy` 版本。切换后发现正文质量或数据完整性回归时，只回滚该来源的 collector 配置到 Firecrawl fallback，不回滚数据库迁移、不删除 first-party 记录。删除旧 `SourceMode`、配置键和生产资源必须在所有来源完成只读回放后另开变更。
