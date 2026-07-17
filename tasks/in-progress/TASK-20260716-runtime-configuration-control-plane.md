# TASK-20260716: 统一运行时配置控制面

## 目标

全面审计并统一 FinBot 配置管理，使外部凭据和代理配置可在后台安全热更新、热测试，同时保留必要的 bootstrap ENV 边界。

## 范围

- 配置分类、加密 Override、版本和审计。
- Provider、代理路由、测试交易账户、信息源凭据运行时解析。
- Provider/Model 自由新增、引用提示、受保护软删除和通用 `API_KEY` 归属。
- 交易所账户及其他应用资源的厂商无关凭据映射与 UI ENV 字段清理。
- Firecrawl 请求级代理节点轮换、独立连接、有限重试与轮换状态观测。
- 信息源创建、编辑、启停、软删除、在线测试、历史保留和资源级加密凭据。
- 配置中心 UI、连通性测试和生效来源状态。
- Liquibase 032、K8S 主密钥、契约和线上验证。
- Liquibase 033 信息源归档契约、代理出口轮换和 Firecrawl 线上成功率验证。
- 定时任务重叠抑制：同一 schedule 存在 `PENDING`/`CLAIMED` 实例时只推进调度游标，不重复入队。
- Worker 周期恢复过期租约，覆盖滚动发布时旧 Pod 在新 Pod 启动后才停止形成的孤儿任务。
- 目标感知代理健康：业务流量只轮转真实通过 Firecrawl/Bybit 探测的节点，UI 区分服务就绪与出口可用。

## 非目标

- UI 不管理数据库密码、运行时 Secret 主密钥、管理员引导密码、Session Secret和内部服务令牌。
- 不开放实盘交易。

## 状态

In progress. 在既有热配置控制面上继续修复 Provider 创建/模型探测和 Firecrawl 完整代理池发现问题；本地实现与门禁完成，等待 CI/GitOps 和生产在线验收。

### 本轮增量（2026-07-17）

- Provider 创建不再要求手填“首个模型”，厂商和模型生命周期解耦。
- 新增未保存 Provider 参数测活：使用 `Base URL + API Key` 调用模型目录，成功后从探测结果选择并导入模型；创建失败保留表单。
- 已保存 Provider 的热测试同时返回待导入模型，模型与费率页不再提供自由文本模型名。
- Liquibase 034 将 `provider_grok_sub2api` 显示为 `sub2api-grok`，将 `provider_gemini_default` 显示为 `sub2api-gemini`，稳定 ID 和工作流引用不变。
- Proxy Gateway 不再永久截取订阅前 `maximumNodes` 个节点；每次刷新轮换候选窗口，并公开完整可用候选数和本轮偏移。
- Firecrawl 对网络异常与 403/429/5xx 一样使用新连接有限重试，最终 `FIRECRAWL_NETWORK_FAILURE` 包含真实尝试次数。

### 本地验证

- Java 26：`clean test bootJar` 通过。
- Web：13 项 Vitest、OpenAPI 88 路径/101 Controller operation contract check、production build 通过。
- Quant：16 项测试通过；Proxy Gateway：21 项测试通过。

### 生产证据（2026-07-17）

- Core 镜像 `f118056e747bc93962d46a68b01e50a66f15365a`，Proxy 镜像 `167cbf4e212f63b2e15468ba7597422f5b6cfa57`。
- ArgoCD revision `4b104225b256b58488dd151e0ec51d9332b176e8` 为 `Synced/Healthy`；Backend、Quant、Web、两个 Proxy 和 PostgreSQL 均单副本 Ready。
- Core CI run `29528418652` 与 Proxy CI run `29525020106` 全部成功，覆盖 PostgreSQL 集成测试、Web 组件/E2E、镜像扫描、签名和 GitOps 更新。
- 生产 system smoke 覆盖 13 个工作区、桌面/移动端溢出、登录、CSRF 业务冲突和 Operations SSE，结果全部通过。
- 周期配置对账已与强制探测分离；跨多个 30 秒对账周期，Proxy generation 不增长，Backend 最近 15 分钟对账告警为 0。管理员显式重新探测仍会推进 generation。
- 状态查询对降级网关返回 HTTP 200 结构化状态；显式重载无可用出口时返回 HTTP 502 ProblemDetail，错误码 `PROXY_GATEWAY_UNAVAILABLE`。
- EIA Firecrawl 在线测试使用实际保存配置执行并返回 HTTP 200 结构化 `FAILED`，错误码 `FIRECRAWL_NETWORK_FAILURE`，未绕过强制代理或伪装成功。

### 外部阻断

- Firecrawl IPv4 代理池：健康节点 `0/32`，最近探测为 29 个 `HTTP_403`、3 个 `HTTP_429`。
- Bybit 代理池：健康节点 `0/4`，全部为 `CONNECTION_ERROR`。
- 解阻条件：提供至少一个能通过对应目标探测的稳定 IPv4 节点池；保存到后台后点击“重新探测”，确认健康节点大于 0，再重跑 Firecrawl 信源和 Bybit Demo 在线测试。
