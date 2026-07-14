# ADR 012: Java 26 主系统、Spring 原生数据栈与 Python 量化服务

状态：Accepted

日期：2026-07-13

## 背景

FinBot 已从研究原型扩展到常驻 Worker、多 Agent 工作流、交易所账户、OMS、风控、模拟执行和永久账本。现有 Python 实现能够运行，但 `PostgresStore` 继承 SQLite Store 契约、跨层字符串状态和同步页面请求内的外部 I/O 已形成结构性约束。用户明确要求采用 Java 最新稳定能力、异步/流式优先、强类型设计，并彻底抛弃历史兼容；量化研究继续使用 Python 生态。

## 决策

1. 主系统使用 Java 26、Spring Boot 4.1、Spring MVC、虚拟线程、Spring Data JDBC、Spring `JdbcClient`、Liquibase 和 PostgreSQL。
2. Python 业务服务仅保留独立量化研究服务，通过 OpenAPI 3.1 描述的内部 HTTP/SSE 与 Java 通信。代理协议适配作为隔离的无状态基础设施控制面运行，不属于业务服务且不拥有数据库权限。
3. 不引入 jOOQ。复杂 SQL 由基础设施层的 `JdbcClient` / `NamedParameterJdbcTemplate` 显式实现，并由真实 PostgreSQL Testcontainers 测试补偿编译期 SQL 校验缺失。
4. 新系统使用独立 Schema 与 `/api/v2`，不实现旧表、旧 API 或 Python Store 的兼容 adapter。
5. 命令异步化，状态事件先持久化再流式发布；SSE 支持基于事件序号的断线续传。
6. Java 26 稳定特性全面使用；preview/incubator API 与交易核心隔离，不成为生产正确性依赖。
7. 领域模型禁止裸字符串状态和通用 Map；使用 record、sealed interface、领域 enum、值对象和专用 ID。
8. Java 是研究任务与事件的权威状态持有者。Python 在单个 `POST` 响应内流式计算，不维护可被另一 Pod 查询的第二套任务状态机。
9. Java 业务代码只依赖 `ProxyRouteResolver` 的 HTTP proxy 契约；VLESS/Hysteria2 解析、订阅刷新和 sing-box 节点健康选择由独立 proxy gateway 负责，Firecrawl 与交易所使用隔离实例并 fail-closed。

## 为什么内部使用 HTTP/SSE

量化输入通过 artifact URI 传递，流中只有阶段、指标和产物元数据，吞吐量不足以抵消 gRPC 双端 stub、`protoc` 和调试工具链的成本。HTTP/SSE 可直接使用 JDK `HttpClient`、FastAPI、OpenAPI、K8S 探针和现有可观测设施。Java 关闭连接即可传播取消；断线后 Java 以相同 idempotency key 重试确定性研究任务。

## 为什么不是 WebFlux

核心持久化使用阻塞 JDBC。将其放入 Reactor event loop 会引入线程切换和阻塞风险。Spring MVC 配合虚拟线程可以保留直接、可调试的同步事务代码，同时让 HTTP、JDBC 和 provider I/O 获得高并发；真正的 token/进度流通过 SSE 与 `Flow.Publisher` 适配。

## 为什么保留 Python 量化

NumPy、Pandas、SciPy、statsmodels、vectorbt 和研究型数据工具的覆盖范围仍优于 Java。量化服务是明确的计算边界，不拥有账户、订单或账本写权限；Java 始终负责风控、审批、OMS 和交易执行。

## Liquibase 约束

- 根 changelog 使用 YAML，PostgreSQL DDL 使用 formatted SQL。
- changeset 一经发布不可修改。
- destructive change 使用 expand-contract 或显式停机迁移。
- schema change、数据回填和应用发布分开观察。
- CI 运行 `validate`、`update`、`update-sql` 和 Testcontainers upgrade test。

## 取舍

- Java 主系统增加代码量，但获得更强的领域类型、事务边界和长期运行治理。
- Spring Data JDBC 保持聚合模型简单；高基数明细不作为聚合子集合，避免删除重建。
- 不做历史兼容降低了迁移复杂度，但要求完整的前端切换、一次性历史导入和明确回滚窗口。
- Java 26 是当前最新 GA，不是 LTS；镜像和 toolchain 必须自动化，项目每六个月执行一次 JDK 升级评估。若供应链或依赖出现阻断，可临时以 Java 25 LTS 构建，但不得引入兼容代码分支。

## 回滚

- Java 与 Python 旧系统在切换前使用独立数据库和流量入口。
- Java 切换失败时回退流量到冻结的 Python 版本，不反向写回 Java 新 Schema。
- Java 数据库保留事故快照；恢复后通过幂等导入重新核对，不使用运行时双写。
