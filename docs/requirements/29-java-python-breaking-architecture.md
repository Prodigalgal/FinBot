# S3 Java 主系统与 Python 量化服务 Breaking Migration

## 目标

将 FinBot 主系统重建为 Java 26 + Spring Boot 4.1 应用，保留 Python 作为独立量化研究服务。新系统以强类型领域模型、异步命令、可恢复事件流、Liquibase 原生 PostgreSQL Schema 和 K8S 常驻运行为基础，不复用 Python 运行时的 SQLite Store 契约、字符串状态、数据库兼容层或旧 API。

## 范围

- Java 主系统负责 Auth、配置、产品库、Watchlist、采集编排、AI 工作流、多 Agent 辩论、交易建议、风控、OMS、Gate/Bybit 模拟执行、账户与永久交易数据仓库。
- Python 量化服务负责指标计算、策略研究、回测、参数搜索、组合优化和统计评估，通过版本化 OpenAPI 3.1 HTTP/SSE 契约与 Java 通信。另有无业务状态的 proxy gateway 控制面负责把 VLESS/Hysteria2 订阅转换为内部 HTTP proxy；它不访问数据库、账户、订单或 AI 凭据。
- Java 使用 Spring Data JDBC 持久化聚合根，使用 Spring `JdbcClient` / `NamedParameterJdbcTemplate` 实现批量 upsert、账本查询、报表、窗口函数和队列抢占。
- Liquibase 是唯一 Schema 变更工具；新系统使用独立数据库或独立 Schema，从零创建，不运行旧 SQLite/PostgreSQL Schema 的兼容迁移。
- 长耗时操作采用 `202 Accepted + run_id`；进度和 Agent 消息通过支持 `Last-Event-ID` 恢复的 SSE 输出。
- Java Worker 通过内部 HTTP `POST` 启动一次无状态研究流，Python 在同一响应中使用 SSE 返回事件；任务结果通过不可变 artifact 引用和结构化终态返回。
- Java 内部使用虚拟线程执行阻塞 JDBC/HTTP I/O；并发 provider、交易所和采集调用通过受限虚拟线程执行器与 `CompletableFuture` 编排。

## 非目标

- 不将 Python 的 `SQLiteStore`、`PostgresStore(SQLiteStore)`、旧表结构或字符串状态复制到 Java。
- 不保持旧 REST API、Python 类名或旧 JSON payload 的运行时兼容。
- 不在交易核心使用 Java preview/incubator API；preview 能力只能放在隔离实验模块。
- 不将阻塞 JDBC 放入 WebFlux event loop；主 API 使用 Spring MVC + 虚拟线程。
- 不把量化计算产生的 `double` 直接写入资金、价格、数量、手续费或保证金账本。

## Java 基线与特性

- Java 26 GA，Gradle toolchain 锁定 `languageVersion=26`。
- 领域 DTO 和值对象使用 `record`。
- 有限状态和事件族使用 `enum`、`sealed interface` 与模式匹配 `switch`。
- 阻塞 I/O 使用虚拟线程；异步结果使用 `CompletionStage`，事件流使用 `Flow.Publisher` 和 SSE adapter。
- JDK `HttpClient` 用于交易所与通用 HTTP adapter，并允许按上游能力启用 HTTP/2 或 HTTP/3。
- `ScopedValue`、Structured Concurrency、Vector API 等 preview/incubator 能力不得进入交易或账本核心。

## 类型规则

- 每个领域使用独立状态类型：`DecisionAction`、`ProposalStatus`、`ApprovalStatus`、`OrderStatus`、`WorkflowRunStatus`、`ResearchRunStatus` 不得互换。
- `WATCH` / `HOLD` 是决策动作，不是订单状态。
- 分析建议使用 `TradeProposal`，其类型本身不可提交；只有 `ApprovedTradeIntent` 可以进入 OMS。
- 资金与交易数值使用 `BigDecimal` 值对象：`Money`、`Price`、`Quantity`、`Percentage`。
- 量化内部可使用 `double` / primitive arrays，但必须在量化服务边界进行精度明确的转换。
- ID 使用领域专用 record，禁止跨领域裸 `String` ID。
- Domain、Application 和公开 API 不使用 `Map<String, Object>`；可扩展 JSON 仅允许存在于 provider adapter 的原始载荷归档。

## 异步与流式契约

1. 命令提交写入 run 与 outbox 后立即返回 `202`。
2. Worker 领取任务后按幂等键推进状态，所有重试必须可重放。
3. 每个状态变化生成单调递增事件序号，并持久化后再推送。
4. SSE 客户端携带 `Last-Event-ID` 重连时，从 PostgreSQL 回放缺失事件，再切换到实时流。
5. LLM token、Agent 消息和阶段进度分级流式输出；内部 reasoning 不原样暴露，只输出可审计摘要。
6. Python 量化流返回 accepted、progress、artifact、completed 或 failed 事件；Java 必须处理断流、超时、取消和重复终态。
7. Python 不保存权威任务状态；Java 关闭 HTTP 响应即取消计算，断流后以同一 idempotency key 重试，所有事件由 Java 持久化。

## PostgreSQL 与 Liquibase

- 新库使用 `bigint generated always as identity` 作为高基数内部主键，领域公开 ID 使用有序字符串 ID 并加唯一约束。
- 时间统一使用 `timestamptz`，货币与价格使用带明确 scale 的 `numeric`，布尔使用 `boolean`，扩展载荷使用 `jsonb`。
- equality 条件在复合索引前，时间范围在后；账本和事件流使用 `(aggregate_id, sequence)` 唯一约束。
- 外部 HTTP 调用不得位于数据库事务内。
- 批量事实使用 PostgreSQL `ON CONFLICT` 或 COPY，禁止 select-then-insert。
- `db.changelog-master.yaml` 只负责组合；复杂 PostgreSQL DDL 使用 Liquibase formatted SQL，并提供显式 rollback 或不可逆说明。
- 已发布 changeset 不得修改，只能追加。

## 模块边界

```text
apps/
  web/                    # React + TypeScript
services/
  backend/
    finbot-domain/        # 纯 Java 领域模型
    finbot-application/   # use case、port、异步契约
    finbot-infrastructure/# JDBC、Liquibase、HTTP、exchange、AI adapter
    finbot-bootstrap/     # Spring Boot、REST、SSE、配置、装配
    finbot-migration/     # 一次性只读历史导入器
  quant/                  # Python 量化研究服务
  proxy-gateway/          # sing-box 代理控制面
contracts/
  quant-research.openapi.yaml
platform/
  k8s/                    # Kustomize 与 Argo CD 运行资源
```

依赖方向固定为 `bootstrap -> infrastructure -> application -> domain`；Domain 不依赖 Spring、数据库、HTTP、AI SDK 或 Python。

## 验收标准

- Java 26 toolchain 构建通过，Domain 模块无 Spring 依赖。
- Liquibase 能在空 PostgreSQL 上完整建库，重复执行无额外变更。
- 状态类型互不兼容，`TradeProposal` 无法直接进入 OMS。
- 阻塞 JDBC 在虚拟线程上运行；SSE 可通过事件 ID 断线续传。
- Java-Python OpenAPI 契约、FastAPI request/response schema 与 Java HTTP client 通过 SSE contract test。
- Testcontainers 使用真实 PostgreSQL 验证约束、幂等 upsert、并发领取和事务边界。
- Python 参考实现仅用于 golden-master 对照，不成为 Java 运行时依赖。

## 迁移原则

- Python 系统冻结为只读参考基线；迁移期间不新增结构性功能。
- 业务历史如需保留，使用一次性、可校验的离线导入，不建设运行时双读双写。
- 新 Java API 使用 `/api/v2`；前端随 Java API 同步切换。
- 切换前必须完成同输入双跑、交易事实核对、模拟订单幂等、故障恢复和回滚演练。
