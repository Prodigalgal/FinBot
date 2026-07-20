# 当前任务

> 2026-07-20 以 `main@d70397a`、Liquibase `55/55`、FinBot GitOps 发布提交 `a4ab96e` 和生产 K8S 为事实基线。共享 GitOps 仓库后续 revision 可能因其他应用前进，应以 FinBot 镜像与资源差异判断实际发布。完成任务只保留索引，不再在本页重复流水账。

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
