# ADR 026：可复用的爬虫请求头 Profile（伪装与绕过）

## 状态

Accepted，2026-07-19；**修订为 A+B+C1+C2+C3**（伪装、挑战分类、Playwright browser worker、可选外部绕过；删除原透明爬虫限制条文）。

## 背景

不同信息源需要不同的内容协商、浏览器身份、会话上下文甚至 CAPTCHA/WAF 应对策略。请求头与绕过配置不应散落在 Collector 或环境变量中；应通过可复用 Profile 统一管理，并在下一次请求热生效。

## 决策

1. 继续使用独立的 `CrawlerHeaderProfile` 聚合，由 `InformationSource.crawlerHeaderProfileId` 绑定；控制面 CRUD + 乐观版本热更新。
2. Profile 描述完整伪装身份，而不仅是透明 FinBot 身份：
   - `userAgent`：任意浏览器或爬虫 UA
   - 可选 `accept` / `acceptLanguage`
   - `additionalHeaders`：完整附加头（含 `Sec-*`、`X-Forwarded-*`、`Cookie`、`Authorization`、`Origin`、`Referer`、`Host` 与 hop-by-hop；仅拒绝 framing 托管的 `Content-Length` / `Transfer-Encoding`）
   - `browserTemplate`：`NONE | CHROME_WINDOWS | CHROME_MAC | FIREFOX_WINDOWS | EDGE_WINDOWS | CUSTOM`，用于补齐一致的 Client Hints / Sec-Fetch 套装
3. 跨 origin 重定向策略可配置：
   - `retainSensitiveHeadersOnCrossOriginRedirect=true` 时保留全部敏感头
   - 否则默认剥离 `Authorization`/`Cookie`/`Origin`/`Referer`/token 类头，但可用 `crossOriginRetainHeaders` 白名单保留
4. **C1 访问挑战分类（默认始终启用，与是否绕过无关）**：
   - `CrawlerAccessChallengeDetector` 识别 Cloudflare Turnstile/Managed、reCAPTCHA、hCaptcha、Anubis（含 Oh noes / within.website / 200 bot wall）、DataDome、PerimeterX、通用 JS challenge、429 限流与未知 401/403 阻断
   - **HTTP 200 挑战页**（如 baresearch Anubis）同样分类，不再当作成功内容
   - 错误码形态：`{PREFIX}_CHALLENGE_*` / `{PREFIX}_ACCESS_BLOCKED` / `{PREFIX}_RATE_LIMITED` / `{PREFIX}_HTTP_{status}`；公共 SearXNG 为 `PUBLIC_SEARXNG_INSTANCE_CHALLENGE_*`
   - `SourceCollectionException` 携带 `challengeKind`；失败 `source_fetch_attempt.parser_version` 记为 `challenge/<kind>`
   - C1 **不执行** challenge、不自动求解
5. **C2 浏览器 worker（first-party Playwright）**：
   - 独立服务 `finbot-browser-worker`，stealth 启动参数 + 反 webdriver 注入 + 更长 Anubis 结算窗口
   - 挑战未解除时 `detail=challenge-unresolved`，Java 侧 fail-closed，不假装成功
   - Profile `captchaBypassProvider=BROWSER_WORKER`；集群内 URL 默认 `http://finbot-browser-worker:8082`
   - 鉴权 `FINBOT_BROWSER_WORKER_TOKEN`（可回退 `FINBOT_JAVA_SERVICE_TOKEN`）
6. **C3 外部 CAPTCHA/WAF 求解（按 Profile 显式开启）**：
   - `captchaBypassEnabled` + `captchaBypassProvider`（`CAPSOLVER` / `TWOCAPTCHA` / `FIRECRAWL_BROWSER` / `BROWSER_WORKER`）
   - 仅在 C1 已识别且可求解的 challenge 上尝试一次；失败仍保留 C1 分类错误
   - 外部求解器密钥：`FINBOT_CAPSOLVER_API_KEY`、`FINBOT_TWOCAPTCHA_API_KEY`、`FINBOT_FIRECRAWL_*`
7. 进程启动时设置 `jdk.httpclient.allowRestrictedHeaders`（`host,connection,expect,upgrade,via,te,trailer`），使伪装配置中的受限头可被 JDK HttpClient 发送。
8. 系统默认 `header_default` 仍可保持较保守身份，但**不再强制** FinBot UA 或禁止浏览器伪装；使用中 Profile 不可停用/删除的并发保护保持不变。
9. UI、OpenAPI 与 PostgreSQL 为同一控制面契约；变更见 `052-crawler-header-camouflage` 与 `053-crawler-browser-worker-provider`。

## 生产启用状态

- 052/053 已执行，`finbot-browser-worker` 单副本和 Chromium health 已上线。
- C1 不依赖 Profile 开关，已经在生产对 Cloudflare、Anubis/JS wall 和普通阻断输出稳定分类。
- 当前只有 `header_default`，绑定 62 个来源，`browserTemplate=NONE`、`captchaBypassEnabled=false`、`captchaBypassProvider=NONE`；C2/C3 solve 调用为 0。
- Browser Worker 每个 context 已强制绑定 `WEB_CRAWL` HTTP proxy，NetworkPolicy 只允许 DNS 和 proxy gateway 8080；C2/C3 solve 调用仍为 0，生产来源在受控 smoke 前不得启用 `BROWSER_WORKER`。

## 取舍

- C1 启发式分类提高可观测性，但站点改版可能导致误判；未知 401/403 统一记为 `ACCESS_BLOCKED`，不伪装成成功。
- 伪装与 C3 自动绕过提高对抗限流/challenge 的成功率，但增加密钥依赖与上游 ToS 风险；默认 Profile 关闭绕过，按信源显式开启。
- 浏览器模板降低指纹不一致概率，但不伪造 TLS/JA3；深度指纹对抗仍依赖出口代理与可选 Firecrawl 浏览器通道。
- 敏感头可跨域保留，便于防爬站点的会话链路，但扩大了凭据泄漏面；仅对显式配置的 Profile 生效。
- C2 已有独立 semaphore、等待/拒绝计数和 solver/Java adapter fixture；真实生产 solve、证据落库和关闭回滚仍是启用门禁，不因 Pod Ready 而视为完成。

## 回滚

1. 将信源切回 `header_default` 并关闭 `captchaBypassEnabled`。
2. 回滚应用版本；如需移除列，执行 052 rollback（先确认无自定义伪装 Profile 依赖新列）。
3. 历史采集证据不受影响。
