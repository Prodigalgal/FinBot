# TASK-20260719：Crawler Challenge 运行态收口

## 目标

在已发布的 C1/C2/C3 代码基础上，完成 Browser Worker 的代理隔离、容量边界、直接测试和生产显式启用验收，使“能配置”升级为“可安全运行、可观测、可回滚”。

## 范围

- Browser Worker 出站显式绑定 `WEB_CRAWL` 代理，代理不可用时禁止直连。
- 为 Browser Worker 增加全局并发上限、排队/拒绝指标和超时边界。
- 直接测试 `BrowserChallengeSolver`、Java `CompositeCrawlerAccessChallengeBypassGateway`、cookie/UA 回放和 unresolved challenge。
- 选择受控测试来源，通过独立 Header Profile 显式启用 `BROWSER_WORKER`，执行一次真实 solve 与关闭回滚。
- UI 和运行状态区分 C1 已分类、C2/C3 未启用、求解成功、部分成功与失败。

## 非目标

- 不默认给全部来源开启绕过，不把 Browser Worker 设为隐式 fallback。
- 不绕过登录墙、付费墙或需要人工同意的站点条款。
- 不改变研究、量化、辩论和交易契约。

## 影响文件

- `services/browser-worker`：代理、并发、solver 测试和观测。
- `services/backend/finbot-infrastructure/ingestion`：Browser Worker adapter 与错误模型。
- `platform/k8s`：出站路由、NetworkPolicy、资源与探针。
- `apps/web`、OpenAPI：运行状态和配置提醒。

## 验收标准

- Browser Worker 所有目标请求均经过已验证的 `WEB_CRAWL` 出口，无健康代理时 fail-closed。
- 并发、超时、challenge 未解除、无 cookie、跨域最终 URL 和 token 错误均有直接自动化测试。
- 生产完成一次显式 Profile 启用、真实挑战处理、cookie 回放、采集落库和 Profile 关闭回滚。
- CI、镜像扫描、Kustomize、Argo CD、Pod、API 和浏览器 smoke 全部通过。

## 测试方式

- Browser Worker pytest 与 Playwright fixture。
- Java adapter/WireMock、CrawlerTransport 与 PostgreSQL Profile 集成测试。
- Kustomize/NetworkPolicy 静态门禁和生产目标级代理验证。
