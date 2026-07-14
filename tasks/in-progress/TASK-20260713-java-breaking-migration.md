# TASK-20260713 Java Breaking Migration

状态：In Progress

## 目标

按 `docs/requirements/29-java-python-breaking-architecture.md` 和 ADR 012 建立 Java 26 主系统基础，保留 Python 量化服务，完成强类型、异步/流式与 Liquibase 新 Schema 的第一条可运行纵向切片。

## 第一阶段验收

- [x] Java 26 + Spring Boot 4.1 多模块构建
- [x] 强类型决策、建议、审批、OMS、量化请求/事件状态和值对象
- [x] 异步 run command 与可恢复 Workflow Event SSE
- [x] Liquibase 空库基线与 PostgreSQL Testcontainers 用例
- [x] Java-Python OpenAPI 3.1 HTTP/SSE 流式契约
- [x] 构建、单测、类型检查、契约校验和 secret scan

## 本轮验证

- JDK 26.0.1 + Gradle Wrapper 9.6.1：`clean test bootJar` 通过并成功存储 configuration cache。
- Python 3.13：Ruff、`mypy --strict`、pytest、`pip check` 和 `compileall` 通过。
- OpenAPI 3.1：`openapi-spec-validator contracts/quant-research.openapi.yaml` 通过。
- Java-Python 边界：Python ASGI SSE 测试和 Java JDK `HttpClient` SSE contract test 通过。
- Liquibase：离线 changelog 解析与校验通过；真实 PostgreSQL Testcontainers 用例已实现，本机无 Docker 时按配置跳过，必须在 CI/K8S runner 执行。
- 安全：新增范围 secret pattern scan 无命中；服务间 token 仅允许通过 `FINBOT_QUANT_SERVICE_TOKEN` 注入。

第一阶段纵向切片已完成；任务继续保持 In Progress，直到后续迁移顺序全部完成并完成 K8S 切流。

## 完整迁移执行

逐能力状态、波次、切流门禁和 Definition of Done 统一维护在
`docs/migrations/010-java-breaking-exec-plan.md`。第一阶段纵向切片不是迁移终点；只有
CAP-01 至 CAP-19 全部完成、历史导入核对、前端无 `/api/v1`、生产无 Python Web/Worker
和 SQLite 入口、K8S 三轮闭环通过后，本任务才能归档。

当前波次：生产切流。CAP-01 至 CAP-18 已完成实现与验收；CAP-19 正在等待 GitHub Actions 镜像发布、Argo CD 同步和生产运行态验收。

## 生产候选验收

- Java 26 全模块 `clean test`、两个 bootJar 构建通过；PostgreSQL 时间参数统一在 JDBC 边界转为 UTC `OffsetDateTime`。
- Quant：Ruff、mypy、8 tests、OpenAPI 3.1 校验通过；Proxy Gateway：Ruff、mypy、3 tests 通过。
- 集群真实 SQLite 快照离线导入：71 张表，129244 源行与 129244 归档行一致，11755 行转换为规范化业务事实。
- React 仅使用 `/api/v2`，生产构建和 8 页 Playwright smoke 通过；桌面与 390px 移动端均无横向溢出或控制台错误。
- 旧 SQLite 清理脚本通过“无完成标记拒绝删除”和“有完成标记完整删除”两条集群内测试。
