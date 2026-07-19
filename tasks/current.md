# 当前任务

## S3：统一运行时配置控制面

- 状态：Provider 与 Firecrawl 可靠性修复已生产发布；Firecrawl keyless 被外部 IP 风控阻断（2026-07-17）。
- 已完成：Provider 创建与模型创建解耦；新增未保存参数测活和 `/models` 探测导入；移除手填模型名；默认厂商展示名迁移；Firecrawl 网络异常三次换连接重试；代理候选窗口覆盖完整订阅池。
- 本地门禁：Java `clean test bootJar`、Quant 16 项、Proxy 21 项、Web 13 项、OpenAPI contract check 与 Web build 全部通过。
- 生产：Core/Proxy `f50add3`，ArgoCD revision `323fe5e` 为 `Synced/Healthy`；Liquibase 034 和 Provider 新建前测活均通过。
- 外部阻断：Firecrawl 代理池共 2494 个候选，两个独立 32 节点窗口均为 `403/429`；原始 403 明确表示出口 IP 可疑，需更换出口或配置 Firecrawl API Key，仍保持强制代理。
- 详情：[`in-progress/TASK-20260716-runtime-configuration-control-plane.md`](./in-progress/TASK-20260716-runtime-configuration-control-plane.md)。

## S3：自研信息采集内核

- 状态：first-party 核心、多领域国内外目录 v4、内部/公共 SearXNG 与 AI Web Search 已生产运行；047-049、长 source ID 异步采集与在线测试异步化均已完成生产验收（2026-07-19）。
- 目标：由 FinBot 自己完成请求、代理路由、静态协议解析、抽取、规范化和观测；Firecrawl 与 first-party 是独立渠道，默认关闭 Firecrawl，不能作为 first-party 失败后的隐式 fallback。
- 已完成：固定 v1-v3 历史并追加 Liquibase 045/046 v3（61 个来源、国内外综合新闻、科技/金融/农业/医疗/能源/安全/科研、8 个交易所公告）、047 SearXNG engine shortcut 路由修正、048 显式可用引擎冗余及 049 公共 SearXNG 实例池目录 v4（62 个来源）；manifest append-only；`HTML_DOCUMENT`、`RSS`、`JSON_API`、`SITEMAP`、`SEARCH_DISCOVERY` 与 `AI_WEB_SEARCH`；内部 SearXNG 多引擎单副本及公共实例池均强制 `WEB_CRAWL` 代理；Grok/Gemini 搜索 binding、引用校验和 token 审计；统一 `CrawlerTransport`；ContentEnvelope/稳定 `blockId`；多 Agent 清洗/压缩引用门禁；全局/来源/单主机背压；SSRF、fail-closed、重试/重定向与凭据隔离；来源运行健康、采集中断恢复；Web 来源筛选和 AI binding 编辑；Java/Web/OpenAPI/Kustomize 测试。
- 当前边界：v4/047-049 已完成真实 PostgreSQL、K8S、ArgoCD、代理和控制面验证；内部 SearXNG smoke 返回国际 16 条、国内 10 条，异步采集分别完成 18 条和 10 条。公共池经代理成功读取 searx.space 的 88 个目录项并筛出 30 个静态合格候选，但诚实 JSON 请求的三实例在线尝试最终为 `PUBLIC_SEARXNG_POOL_EXHAUSTED`（末次 HTTP 403），随后按设计进入 `PUBLIC_SEARXNG_POOL_COOLDOWN`；不伪装浏览器、不绕过 CAPTCHA/WAF。新闻专用引擎及 Baidu/Sogou 仍可能受解析错误、CAPTCHA 或限流影响；任何 `/healthz` 都不能替代真实结果 smoke。Firecrawl 私有四节点保持 fail-closed 且默认关闭；GDELT/SEC 的上游限流或出口阻断会显示为明确的 `429/403`，不会被伪装成成功；本阶段不引入浏览器渲染，不改变研究和交易工作流。
- 运行态修复：`ACCOUNT_SYNC` 交易所只读请求已增加重新签名、HTTP/1.1、有限退避、连接失效清理；可选交易所代理失败后按 `allow_direct` 受控直连，Firecrawl/Web Crawl 仍 fail-closed。
- 运行态修复：手动来源采集使用固定 `manual-ingestion` scope，并将 source ID 纳入被哈希的 client key；合法长 source ID 不再因 40 字符 scope 上限导致异步 `/collect` 必然 400。
- 运行态修复：信息源 `/test` 改为独立 `source-test` 幂等域的持久化异步任务，Web 轮询终态并关联本次采集运行，避免 SearXNG 长查询穿过 Cloudflare 时同步请求 504；生产公网首响应 `202` 用时 964 ms，任务首次尝试完成，采集 20 条、新增 7 条、去重 13 条，保留刷新后后台可追踪性。
- 需求：[`../docs/requirements/2026-07-18-first-party-crawling-architecture.md`](../docs/requirements/2026-07-18-first-party-crawling-architecture.md)。
- 默认目录 v2：[`../docs/requirements/2026-07-18-default-source-catalog-v2.md`](../docs/requirements/2026-07-18-default-source-catalog-v2.md)。
- 默认目录 v3：[`../docs/requirements/2026-07-18-multi-domain-source-catalog-v3.md`](../docs/requirements/2026-07-18-multi-domain-source-catalog-v3.md)。
- 多领域搜索决策：[`../docs/decisions/024-multi-domain-news-search-discovery.md`](../docs/decisions/024-multi-domain-news-search-discovery.md)。
- 公共实例池需求：[`../docs/requirements/2026-07-19-public-searxng-instance-pool.md`](../docs/requirements/2026-07-19-public-searxng-instance-pool.md)。
- 公共实例池决策：[`../docs/decisions/025-public-searxng-instance-pool.md`](../docs/decisions/025-public-searxng-instance-pool.md)。
- 决策记录：[`../docs/decisions/023-free-structured-source-catalog.md`](../docs/decisions/023-free-structured-source-catalog.md)。
- 决策：[`../docs/decisions/022-first-party-crawling-core.md`](../docs/decisions/022-first-party-crawling-core.md)。
- 任务：[`in-progress/TASK-20260718-first-party-crawler.md`](./in-progress/TASK-20260718-first-party-crawler.md)。

## S3：压缩后分裂的双环境研究工作流

- 状态：进行中（2026-07-15）。
- 目标：共享一次证据采集/清洗/压缩，在不可变快照后分裂为实盘研究与模拟自动交易两个独立工作流。
- 隔离：所有交易所的 `LIVE/TESTNET/DEMO` 在行情、artifact、预测、账户和交易数据上硬隔离；产品模型保持共享简单。
- 已完成：压缩后并行双分支、分支独立工作流选择、环境化行情/预测/风控/OMS 外键、交易所能力矩阵、六策略量化注册表、实时双流事件面板、实盘 shadow PnL。
- 待完成：生产 030 迁移、真实双分支 smoke、CI/GitOps 发布验收。
- 规格：[`../docs/requirements/32-segmented-dual-environment-research.md`](../docs/requirements/32-segmented-dual-environment-research.md)。
- 决策：[`../docs/decisions/016-segmented-research-and-exchange-environments.md`](../docs/decisions/016-segmented-research-and-exchange-environments.md)。

## S3：Java Breaking Migration 生产收口

- 状态：进行中。
- 目标：完成 Java/PostgreSQL 单一生产运行时、真实研究闭环、单副本发布验证和迁移资源清场。
- 详情：[`in-progress/TASK-20260713-java-breaking-migration.md`](./in-progress/TASK-20260713-java-breaking-migration.md)。
- 验收：[`../docs/migrations/010-java-breaking-exec-plan.md`](../docs/migrations/010-java-breaking-exec-plan.md)。

## S3：Monorepo 目录与发布路径整理

- 状态：进行中（2026-07-14）。
- 目标：按 `apps / services / platform / contracts / docs / tasks` 明确所有权，重写 README 和文档索引，并保持 CI/CD 与生产行为不退化。
- 非目标：不修改 API、数据库 Schema、K8S 资源名、镜像名、Secret key 或交易策略。
- 影响：仓库路径、Docker build context、GitHub Actions、GitOps 同步源和文档链接。
- 验收：见 [`../docs/decisions/013-monorepo-layout.md`](../docs/decisions/013-monorepo-layout.md)。

## 既有专案

- P0-P1 工程质量收口：[`in-progress/P0-P1-quality-closure.md`](./in-progress/P0-P1-quality-closure.md)。
- P3 产品研究工作台：[`in-progress/P3-product-research-workspace.md`](./in-progress/P3-product-research-workspace.md)。
- PostgreSQL + SSE breaking migration：[`in-progress/TASK-20260713-postgresql-sse.md`](./in-progress/TASK-20260713-postgresql-sse.md)。

旧 Python 时代的完整任务流水账已归档到 [`done/legacy-python-project-log.md`](./done/legacy-python-project-log.md)，不再作为当前运行入口。
