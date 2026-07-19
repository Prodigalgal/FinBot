# TASK-20260719：搜索发现质量与请求头加固

## 目标

消除批量采集查询词重复拼接，补齐自建 SearXNG 的结果引擎与不可用引擎归因，在低信任搜索摘要进入证据仓库前执行确定性质量门，并统一 first-party 爬虫请求头策略。

## 范围

- 统一查询编译边界：应用服务只生成一次最终查询，下游 Collector/Provider 不再重复调用 `defaultQuery`。
- 自建与公共 SearXNG 使用同一套引擎元数据提取规则。
- `SEARCH_DISCOVERY` 统一经过质量门，拒绝查询回显、信息量不足、未来时间戳及明显不相关的 T4 结果，并为接受结果记录评分与候选统计。
- 新增可复用 `CrawlerHeaderProfile` 控制面；信源显式绑定 Profile，管理员可通过 UI 增删改，下一次请求热生效。
- `CrawlerTransport` 统一解析 Profile 中透明可识别的 `User-Agent`、可选 `Accept`、`Accept-Language` 和安全附加头，校验请求头并在跨域重定向时剥离敏感信息。

## 非目标

- 不将 SearXNG、Firecrawl、AI Web Search 或正文抓取互相设为隐式 fallback。
- 不随机伪装浏览器指纹，不伪造客户端 IP，不绕过 CAPTCHA、WAF、登录墙或站点访问策略。
- 不修改代理 fail-closed、信源信任等级、研究工作流或交易流程。
- 本批次不实现公共实例池冷却状态持久化和搜索结果正文自动抓取。

## 影响文件

- `finbot-application`：查询编译边界、Header Profile 用例和对应测试。
- `finbot-infrastructure/ingestion`：Header Profile 持久化、请求头策略、SearXNG 元数据、搜索质量门及测试。
- `finbot-bootstrap`、OpenAPI 与 Web：Profile CRUD、信源绑定和管理面板。
- `tasks/current.md` 与自研采集任务记录：完成状态和验证证据。

## 验收标准

- 批量与单来源采集的最终查询都只包含一次来源默认查询和一次研究焦点。
- 自建 SearXNG 证据包含 `search_result_engines`、`search_unresponsive_engines`；全部引擎不可用且无结果时返回明确错误。
- 明显查询回显、低信息量和 T4 零相关结果不会进入 `raw_evidence`，接受结果包含质量评分与过滤计数。
- Header Profile 可复用、可热更新并显示使用数量；使用中的 Profile 不可删除或停用，信源不能绑定不存在或已停用的 Profile。
- 所有 `CrawlerTransport` 请求统一携带 Profile 解析后的安全 `User-Agent`、`Accept`、`Accept-Language`；非法、伪造身份、凭据型附加头和 CRLF 请求头在保存及发网前失败。
- 跨 origin 重定向不转发 Authorization、Cookie、API Key、Token、Origin 或 Referer。
- 相关单测、后端全量测试与构建通过。

## 测试方式

- `./gradlew :finbot-application:test :finbot-infrastructure:test`
- `./gradlew clean test bootJar`
- 对修改后的 diff 执行敏感头、重复 `defaultQuery` 和静默异常静态检查。

## 当前验证证据

- Java：`clean test bootJar` 通过；Profile、请求头策略、SearXNG、Firecrawl、质量门和 Liquibase 离线校验均纳入测试。
- Web：Vitest 8 个测试文件 / 18 个测试通过；`npm run contract:check` 校验 91 条路径、106 个 Controller operation；`npm run build` 通过。
- 静态检查：`defaultQuery` 在应用服务只有一个编译点；采集模块没有残留硬编码 User-Agent、伪造转发头或跨域敏感头转发；`git diff --check` 无错误。
- PostgreSQL Testcontainers 集成测试在当前 Windows 工作站因 Docker 不可用而按项目 Assumption 跳过，需在 CI 或 K8S 发布前用真实 PostgreSQL 执行 050 迁移和 JDBC Profile CRUD smoke。
