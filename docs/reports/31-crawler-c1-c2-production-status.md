# Crawler C1/C2 生产状态报告

## 结论

截至 2026-07-20，Crawler A/B Header Profile、C1 challenge 分类、C2 Playwright Browser Worker 和 C3 provider 契约已经合入、构建并部署。C1 已在生产产生真实分类结果；C2 代码已补齐强制代理、容量和 replay 边界，但当前没有生产来源启用，不能描述为已完成真实绕过。

## 代码变化

- 基线：`c4e529c -> 364be37`，6 个提交、62 个文件、约 `+3097/-210`。
- Java：challenge 类型、检测器、bypass port/adapter、Header Profile 浏览器模板、敏感头重定向规则与稳定错误码。
- Python：新增 `services/browser-worker`，使用 FastAPI、Playwright Chromium、隔离 browser context 和内部 Bearer token。
- Web/OpenAPI：Header Profile 可配置浏览器模板、跨域敏感头保留和 bypass provider。
- 数据：052 增加浏览器身份与 bypass 字段，053 增加 `BROWSER_WORKER` provider。
- 平台：新增单副本 Browser Worker Deployment/Service/NetworkPolicy 和独立镜像发布。
- 运行时：Browser Worker 每个 Playwright context 强制使用 `WEB_CRAWL` HTTP proxy；NetworkPolicy 只允许 DNS 与 proxy gateway 8080，容量耗尽返回 429，未就绪返回 503。
- 测试：Browser Worker 15 个 pytest 用例通过；Java application/infrastructure 定向测试通过，覆盖 proxy 注入、代理失败 fail-closed、容量、认证、跨域最终 URL、畸形 cookie replay 和真实 cookie 回放。

## 自动化与发布证据

- Core CI run `29718029224` 成功，verify、Backend、Quant、Web、Browser Worker 镜像和 GitOps 更新全部通过；Browser Worker mypy、pytest、镜像扫描和签名均通过。
- FinBot 发布写入 GitOps commit `a4ab96efe97f886a192709fee022ca51b71374be`，对应应用 revision 已被 ArgoCD 同步。
- Backend、Web、Quant、Browser Worker、SearXNG、三个 Proxy Gateway 和 PostgreSQL 共 9 个稳态 Pod，均 Ready、零重启。
- Browser Worker health 返回 `engine=playwright-chromium`、`ready=true`。
- Liquibase 052/053 均为 `EXECUTED`，生产累计 55 个 changeset。

## 运行态事实

- 数据库只有 `header_default`：`browserTemplate=NONE`、`captchaBypassEnabled=false`、`captchaBypassProvider=NONE`，绑定全部 62 个来源。
- 最近 12 小时至少产生 19 条 Cloudflare/JS challenge 分类记录，证明 C1 正在工作。
- Browser Worker 仅收到健康探针，`POST /internal/v1/challenge/solve` 为 0；C2 未被真实调用。该状态是当前安全默认，不是 solve 成功证据。
- 公共 SearXNG、Firecrawl 和其他 Collector 失败时不会自动切换到 Browser Worker 或外部求解器。

## 2026-07-20 线上复核与新版本验收

- Argo CD `finbot` 当前 `Synced/Healthy/Succeeded`，Backend、Quant、Web、Browser Worker 均已运行 `sha-d70397a7257fbcd58b2a5bc66a4a4b510a65993b`；9 个稳态 Pod 全部 Ready、零重启。
- Browser Worker health 返回 `ready=true`、`proxy_configured=true`、`proxy_origin=http://finbot-web-crawl-proxy:8080`、`maximum_concurrent_solves=2`、`active_solves=0`。从 Browser Worker Pod 内使用该 HTTP proxy 访问 `https://example.com` 返回 HTTP 200，证明代理服务发现、NetworkPolicy 放行和实际出站链路均生效。
- 生产 ConfigMap 已生效 `FINBOT_DATABASE_POOL_SIZE=32`、`FINBOT_DATABASE_MIN_IDLE=4`、`FINBOT_DATABASE_CONNECTION_TIMEOUT_MS=15000`；Browser Worker egress 仅保留 DNS 与 `finbot-web-crawl-proxy:8080`，proxy gateway ingress 已允许 Browser Worker 访问。
- 生产 `finbot-web-crawl-proxy` 健康检查返回 `nodeCount=16`、`healthyNodeCount=15`、`insecureNodeCount=0`、`lastError=null`；通过 port-forward 的 HTTP CONNECT 到 `example.com` 返回 200，说明代理网关自身可用。
- 数据库 `research_segment` 已有 `EVIDENCE` 87 completed、`LIVE_RESEARCH` 45 completed、`DEMO_AUTOTRADE` 22 completed，证明双分支对象和共享证据模型真实存在；最近定时运行两分支因 AI 上游 503/Provider Error 失败，不能作为完整成功验收。
- 本轮真实双环境 smoke 已通过认证、PoW、数学验证码、CSRF、幂等启动和 Worker 认领：`run_00000mrsrw8ah_f4f67d534a8a0e34bc62` / `task_00000mrsrw8ea_567c136093686752e9b2`。任务在 5/5 次尝试后以 `TerminalWorkflowFailure` 结束，期间 heartbeat 持续更新且没有发生本任务租约丢失。
- 该 smoke 的最终分段为：`EVIDENCE=COMPLETED`，生成 artifact `artifact_00000mrssaptl_f13677e8e72071ccacb8b`；`LIVE_RESEARCH=FAILED` 和 `DEMO_AUTOTRADE=FAILED`，两者均为 `RESEARCH_BRANCH_FAILED`，共享 artifact 引用保持一致。由此确认压缩前共享、压缩后双分支隔离和分支失败隔离真实生效，但尚未得到双分支成功结果。
- 该 smoke 的 AI 调用记录为 267 次失败、5 次完成；失败主要是 `provider_gemini_default/HTTP_503` 与 `provider_mimo_default/PROVIDER_ERROR`，`provider_grok_sub2api` 曾进入真实 `STREAMING`，最终仍被上游失败策略收敛。重试/多席位路径已被真实触发，但当前上游可用性不足。
- 同一生产会话对 `provider_grok_sub2api` 和 `provider_sub2api_default` 执行测活均返回 HTTP 200，分别发现 22 和 20 个模型；这证明可用 provider 已存在，但标准工作流的清洗/压缩主席位仍依赖当前返回 503/Provider Error 的 Gemini/MiMo，后续需在配置面板调整主/兜底席位后再重跑成功验收。

## 未闭环风险

1. 生产尚未完成受控来源的 C2 显式启用、代理出口、真实 challenge/cookie 回放、证据落库和关闭回滚，因此仍不能宣称线上绕过已验收。
2. C1 是启发式检测，站点改版可能产生误判；`challenge-maybe-partial` 不能当作成功采集。
3. 连接池与 heartbeat 代码已部署；观察窗口仍见少量其他定时任务的历史 `Lost lease` 日志，需要继续以 Prometheus 计数和任务状态做长窗口基线，不能仅凭一次 smoke 宣称完全消除。
4. 双环境真实验收依赖至少一个稳定可用的 AI provider；当前 Gemini/MiMo 上游返回 503/Provider Error，DeepSeek 仍按余额不足保持禁用。

## 下一门禁

按 [`37-crawler-challenge-runtime.md`](../requirements/37-crawler-challenge-runtime.md) 完成一次受控生产显式启用/关闭回滚后，才能把 C2 标记为“已启用并验收”。
