# Crawler Challenge 运行时要求

## 状态

进行中。C1/C2/C3 代码和 052/053 migration 已上线；Browser Worker 的代理隔离、容量门禁和直接测试已完成，生产仍尚未启用 C2/C3。

## 目标

让访问挑战处理具备明确的分类、显式启用、代理隔离、容量上限、失败语义和可回滚证据。部署 Browser Worker 不得被解释为默认绕过全部来源。

## 必须满足

1. C1 对普通 HTTP 错误和 HTTP 200 challenge wall 做确定性分类，始终启用且不执行求解。
2. C2/C3 只能由来源绑定的 Header Profile 显式开启；不得作为任何 Collector 的隐式 fallback。
3. Browser Worker 必须使用目标来源要求的 `WEB_CRAWL` 代理，代理无健康出口时禁止直连。
4. Browser Worker 与外部求解器必须有独立并发、超时和请求预算；容量耗尽要返回可观测的拒绝状态。
5. challenge 未解除、无 cookie、最终 URL 越界、token 错误和外部 provider 失败均保持原 C1 分类并 fail-closed。
6. 只允许 Backend 通过内部 Bearer token 调用 Browser Worker；服务不得接收公网流量，不记录 cookie/token 明文。
7. UI 必须区分“C1 已分类”“C2/C3 未启用”“求解成功”“可能部分成功”和“失败”。

## 当前代码事实

- `CrawlerAccessChallengeDetector` 已覆盖 Cloudflare、Anubis、reCAPTCHA、hCaptcha、DataDome、PerimeterX、通用 JS challenge、限流和未知阻断。
- `CrawlerTransport` 在一次初始请求后最多执行一次显式 bypass，并将 cookie/UA 回放到原 transport。
- `finbot-browser-worker` 使用 Playwright Chromium，每次请求创建隔离 context，返回最终 URL、cookie、UA、标题和 challenge hints。
- Browser Worker 通过 `FINBOT_BROWSER_WORKER_PROXY_URL` 将每个 Playwright context 绑定到 `WEB_CRAWL` HTTP bridge；K8S NetworkPolicy 禁止其直接访问公网，仅允许 DNS 和 proxy gateway 8080。
- Python runtime 暴露最大并发、等待、拒绝、成功和失败计数；容量耗尽返回 HTTP 429，健康未就绪返回 HTTP 503。
- Java adapter 校验最终 URL 同源、2xx/3xx 状态、UA 和 cookie，并将畸形 replay material 映射为 fail-closed 的 `CRAWLER_CAPTCHA_BYPASS_FAILED`。
- 生产仅有 `header_default`，62 个来源的 bypass 均关闭，Browser Worker solve 调用为 0。

## 验收

- Browser Worker solver、Java adapter、cookie 回放、unresolved challenge、认证、并发与代理注入均有直接测试。
- 生产选择受控来源显式启用一次，验证代理出口、challenge 解除、证据落库与关闭回滚（仍是本任务未完成的运行态门禁）。
- CI、镜像扫描、Kustomize、Argo CD、Pod/API 和浏览器 smoke 全部通过。
