# 当前任务

> 2026-07-20 以 `main@d70397a`、Liquibase `55/55`、FinBot GitOps 发布提交 `a4ab96e` 和生产 K8S 为事实基线。共享 GitOps 仓库后续 revision 可能因其他应用前进，应以 FinBot 镜像与资源差异判断实际发布。完成任务只保留索引，不再在本页重复流水账。

## P1：单副本执行控制面、服务拆分与完整 OpenAPI

- 决策：保持 Java 等后端服务和 Web 单副本，不引入 Redis、MQ、Outbox 或分布式锁；PostgreSQL 继续作为唯一事实源和持久任务队列。
- 范围：AI 预算原子终态、真实取消、绝对 deadline、有界 Provider 准入、Scheduler 隔离、大型服务拆分、完整 OpenAPI 和单副本 `Recreate`。
- ADR：[`../docs/decisions/033-single-replica-modular-monolith.md`](../docs/decisions/033-single-replica-modular-monolith.md)。
- 任务：[`in-progress/TASK-20260722-java-package-modularization.md`](./in-progress/TASK-20260722-java-package-modularization.md)。
- 状态：代码与离线生产候选验收已完成；本机 149 个 Java 测试通过，20 个 PostgreSQL Testcontainers 用例因无容器运行时跳过，待 GitHub CI 实跑；尚未执行 GitOps 推送和生产 smoke。

## P1：Crawler Challenge 运行态收口

- 状态：C1 分类、C2/C3 配置契约、Browser Worker 和管理 UI 已发布；C2/C3 尚未被任何生产信源启用。
- 已上线：`CrawlerAccessChallengeDetector` 对 200 challenge wall、401/403/418/429、Cloudflare、Anubis、reCAPTCHA、hCaptcha、DataDome、PerimeterX 和通用 JS challenge 分类；失败保持 fail-closed。
- 生产事实：052/053 migration 已执行；Browser Worker `1/1 Ready` 且已运行 `sha-d70397a...`；health 已确认 `proxy_configured=true`，Pod 内通过 `finbot-web-crawl-proxy:8080` 访问 `example.com` 返回 200；62 个信源全部绑定 `header_default`，该 Profile 为 `browserTemplate=NONE`、`captchaBypassEnabled=false`、`provider=NONE`；最近 12 小时已有 Cloudflare/JS challenge 分类，但 Browser Worker solve 调用为 0。
- 已完成：Browser Worker 每个 Playwright context 强制注入 `WEB_CRAWL` proxy；NetworkPolicy 禁止直连；最大并发、等待超时、拒绝计数和 503/429 语义已落地；Python solver、Java adapter、cookie/UA 回放、跨域/畸形 replay、认证和容量测试已通过。
- 待完成：选择受控测试来源完成一次显式启用、挑战解除、cookie 回放、证据落库和关闭回滚的端到端 smoke。
- 规格：[`in-progress/TASK-20260719-crawler-challenge-runtime.md`](./in-progress/TASK-20260719-crawler-challenge-runtime.md)。
- 运行报告：[`../docs/reports/31-crawler-c1-c2-production-status.md`](../docs/reports/31-crawler-c1-c2-production-status.md)。

## P1：生产运行稳定性

- 采集工作区读模型在后台负载下观察到约 3.7-15.8 秒波动，并曾触发两次 60 秒浏览器等待超时；最终 13 页面 smoke 通过。
- 已完成代码侧缓解：`JdbcEncryptedRuntimeSecretStore` 按 scope 做 3 秒只读快照并在热配置写入后立即失效，消除 ingestion workspace 对每个来源逐条查询凭据状态的 N+1；`BackgroundWorkerRuntime` 对 worker/task heartbeat 的瞬时异常隔离并计数，不阻断同轮其他租约续期。
- 已调整 PostgreSQL 连接池默认值：最大 32、最小 idle 4、连接等待 15 秒；生产 Kustomize 已同步并生效，以缓解 AI/账户同步峰值期间的 `active=20` 池耗尽。
- 已补 `BackgroundWorkerRuntimeTest` 的容量、lost-lease、恢复和瞬时 heartbeat 失败用例；线上新镜像已观察到本轮 smoke 任务 heartbeat 持续更新且无租约丢失，但其他定时任务仍有少量历史 `Lost lease`，还需长窗口 Prometheus/任务状态基线。
- 下一步：使用生产 Prometheus/HTTP 证据形成可重复压测基线，再决定是否继续拆分 workspace 读模型或增加持久化汇总表。

## P2：双环境研究真实验收

- 代码与 Schema 已完成：`030-segmented-environment-research` 已在生产执行，`EVIDENCE -> LIVE_RESEARCH / DEMO_AUTOTRADE` 类型、持久化与 API 均存在。
- 已执行真实启动 smoke：认证、幂等、任务认领、共享 artifact 和双分支均有生产数据库证据；最终 `EVIDENCE=COMPLETED`，`LIVE_RESEARCH/DEMO_AUTOTRADE=FAILED`，任务第 5/5 次以 `TerminalWorkflowFailure` 结束。由于 Gemini `HTTP_503`、MiMo `PROVIDER_ERROR`，尚未得到双分支成功结果，因此仍不把该任务标记为完成。
- 需求：[`../docs/requirements/32-segmented-dual-environment-research.md`](../docs/requirements/32-segmented-dual-environment-research.md)。

## P1：AI Provider 容量与慢请求治理

- 目标：将每个 Provider 的最大并发、容量排队等待和单次请求超时纳入后台热配置，默认最大并发 5，并允许高负载厂商使用小时级等待时间。
- 范围：Provider 数据契约、Liquibase、运行时公平限流、工作流计时、OpenAPI、设置页和直接回归测试。
- 非目标：不修改模型/工作流的厂商分配，不自动扩大业务任务总截止时间，不把部署级中间件参数放回 UI。
- 影响文件：`finbot-application`、`finbot-infrastructure`、`finbot-bootstrap`、`contracts/`、`apps/web/`、Kustomize。
- 验收标准：保存后新请求无需重启即采用新容量；排队等待与实际 HTTP 请求分别计时；超时错误可区分；默认并发为 5；API/Web/数据库约束一致。
- 测试方式：Java 单元与 PostgreSQL 集成测试、OpenAPI 契约检查、Web 测试与构建、Kustomize 渲染、CI/GitOps 和生产 API 验证。

## P1：管理员 API Token

- 目标：管理员可在后台申请、查看和吊销 API Token，并通过 `Authorization: Bearer` 调用全部 `/api/v2/**` 管理接口。
- 范围：Token 哈希持久化、到期/吊销/最后使用审计、Bearer 与 Cookie 双认证、Bearer 免 CSRF、管理 API、OpenAPI 和后台面板。
- 非目标：不引入多用户、OAuth、细粒度 scope 或跨租户权限；Token 当前等价于唯一管理员的 `ROLE_ADMIN`。
- 影响文件：identity domain/application/infrastructure、Spring Security、Liquibase、控制面契约、`apps/web` 设置页。
- 验收标准：明文只在创建响应显示一次；数据库无明文；有效 Token 可无 CSRF 调用读写 API；无效/过期/吊销 Token 返回 401 且不回退 Cookie；Cookie 写请求仍要求 CSRF。
- 测试方式：应用单测、Security MockMvc、PostgreSQL 集成测试、OpenAPI、Web 组件/API 测试、真实线上 Token 创建/调用/吊销 smoke。

## P1：Sub2API 直连端点

- 目标：GPT、Grok、Gemini 三个 Sub2API 渠道统一改用跳过 Cloudflare 的 `sub2api-direct.mnnu.eu.org`，改善长连接稳定性。
- 范围：K8S 默认环境变量、现有 Provider 数据迁移、Liquibase 集成断言和生产测活。
- 非目标：不调整模型绑定、API Key、并发配额、超时策略或 MiMo 独立端点。
- 影响文件：`platform/k8s/base/kustomization.yaml`、Liquibase 058、数据库集成测试、CI Kustomize 工具版本。
- 验收标准：三个内置 Provider 的 `base_url` 均为 `https://sub2api-direct.mnnu.eu.org/v1`，模型探测和最小真实调用可达。
- 测试方式：Liquibase/PostgreSQL、Kustomize 渲染、CI、生产数据库查询及 Provider 测活。

## 外部可用性观察

- Firecrawl 保持独立渠道、默认关闭、无健康出口时 fail-closed。
- 公共 SearXNG 池仍可能因运营者 403/429、Anubis 或 limiter 进入冷却；C1 会准确分类，但未显式启用的 C2/C3 不会自动介入。
- DeepSeek 账户余额不足，配置保留但不作为当前强依赖。

## 最近完成并归档

- P0-P1 产品闭环：[`done/P0-P1-quality-closure.md`](./done/P0-P1-quality-closure.md)。
- 产品库、Watchlist 与研究工作台：[`done/P3-product-research-workspace.md`](./done/P3-product-research-workspace.md)。
- First-party crawler 与目录 v1-v4：[`done/TASK-20260718-first-party-crawler.md`](./done/TASK-20260718-first-party-crawler.md)。
- 搜索质量与可复用 Header Profile：[`done/TASK-20260719-search-discovery-quality-hardening.md`](./done/TASK-20260719-search-discovery-quality-hardening.md)。
- 已实现需求的状态与代码证据统一见 [`../docs/requirements/README.md`](../docs/requirements/README.md)。
