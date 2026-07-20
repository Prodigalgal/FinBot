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

- Core CI run `29691886390` 成功，verify、Backend、Quant、Web、Browser Worker 镜像和 GitOps 更新全部通过。
- FinBot 发布写入 GitOps commit `40119fa96aaa0c0a88aa91958cdb77675e227048`；随后共享仓库因其他应用提交前进，FinBot 仍保持 `Synced/Healthy/Succeeded` 且镜像未变化。
- Backend、Web、Quant、Browser Worker、SearXNG、三个 Proxy Gateway 和 PostgreSQL 共 9 个稳态 Pod，均 Ready、零重启。
- Browser Worker health 返回 `engine=playwright-chromium`、`ready=true`。
- Liquibase 052/053 均为 `EXECUTED`，生产累计 55 个 changeset。

## 运行态事实

- 数据库只有 `header_default`：`browserTemplate=NONE`、`captchaBypassEnabled=false`、`captchaBypassProvider=NONE`，绑定全部 62 个来源。
- 最近 12 小时至少产生 19 条 Cloudflare/JS challenge 分类记录，证明 C1 正在工作。
- Browser Worker 仅收到健康探针，`POST /internal/v1/challenge/solve` 为 0；C2 未被真实调用。该状态是当前安全默认，不是 solve 成功证据。
- 公共 SearXNG、Firecrawl 和其他 Collector 失败时不会自动切换到 Browser Worker 或外部求解器。

## 2026-07-20 只读复核

- Argo CD `finbot` 当前 `Synced/Healthy`，但仍运行旧镜像 `sha-364be377...`；新代理隔离代码尚未发布到生产。
- 生产 `finbot-web-crawl-proxy` 健康检查返回 `nodeCount=16`、`healthyNodeCount=15`、`insecureNodeCount=0`、`lastError=null`；通过 port-forward 的 HTTP CONNECT 到 `example.com` 返回 200，说明代理网关自身可用。
- 旧 Browser Worker NetworkPolicy 仍只允许公网 TCP 80/443，未允许到 `finbot-web-crawl-proxy:8080`；从旧 Browser Worker Pod 访问该 Service 超时，符合旧策略阻断事实。新 Kustomize 已改为仅允许 DNS + proxy 8080，必须随新镜像一起发布后复测。
- 数据库 `research_segment` 已有 `EVIDENCE` 87 completed、`LIVE_RESEARCH` 45 completed、`DEMO_AUTOTRADE` 22 completed，证明双分支对象和共享证据模型真实存在；最近定时运行两分支因 AI 上游 503/Provider Error 失败，不能作为完整成功验收。

## 未闭环风险

1. 生产尚未完成受控来源的 C2 显式启用、代理出口、真实 challenge/cookie 回放、证据落库和关闭回滚，因此仍不能宣称线上绕过已验收。
2. C1 是启发式检测，站点改版可能产生误判；`challenge-maybe-partial` 不能当作成功采集。

## 下一门禁

按 [`37-crawler-challenge-runtime.md`](../requirements/37-crawler-challenge-runtime.md) 完成一次受控生产显式启用/关闭回滚后，才能把 C2 标记为“已启用并验收”。
