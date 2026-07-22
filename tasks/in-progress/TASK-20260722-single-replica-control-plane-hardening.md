# TASK-20260722：单副本执行控制面、服务拆分与完整 OpenAPI

## 目标

落实 ADR-033，在不引入 Redis、MQ 或新业务微服务的前提下，修复 AI 执行和 Worker 恢复边界，
拆分大型服务职责，并把控制面 OpenAPI 提升为完整的请求/响应契约。

## 范围

- AI 调用终态、用量与预算释放的原子事务和启动恢复。
- 绝对 deadline、真实 HTTP 流取消、Provider 有界准入与运行指标。
- Worker lease、任务终态竞态和 Scheduler 隔离。
- Workflow、Compression、Trading、RuntimeConfiguration 与 Workspace Query 的职责拆分。
- 完整 OpenAPI Schema、Java API 边界和手写 TypeScript 类型契约校验。
- 单副本 `Recreate` K8S 约束和真实生产候选验证。

## 非目标

- 不引入 Redis、MQ、Outbox、分布式锁或分布式限流。
- 不拆分 Java Backend 网络服务，不改变 Quant HTTP/SSE 边界。
- 不改变产品研究优先、模拟交易用于验证的业务定位。
- 不删除历史交易、研究、工作流或采集数据。

## 主要影响文件

- `services/backend/finbot-application`
- `services/backend/finbot-infrastructure`
- `services/backend/finbot-bootstrap`
- `services/backend/finbot-domain`
- `contracts/finbot-control-plane.openapi.yaml`
- `apps/web`
- `platform/k8s`
- `docs/decisions/033-single-replica-modular-monolith.md`

## 验收标准

1. 孤儿 AI 调用恢复在同一事务中释放预算，且不会重复扣减工作流预留。
2. AI 超时、任务取消和 lease 丢失会关闭底层 HTTP 流并释放 permit。
3. Provider 队列有界，配置热更新有效，所有等待和重试遵守绝对 deadline。
4. Worker lease、控制循环和 SSE 使用独立 Scheduler；完成竞态无误报。
5. 关键大型服务按职责拆分，依赖方向保持稳定，无业务行为静默删除。
6. OpenAPI 全部公开操作拥有具体成功响应和错误模型，CI 校验链阻止手写类型字段漂移。
7. 所有部署保持单副本且更新期间不产生双 Backend。
8. Java、Web、Quant、Proxy、Kustomize 和线上 smoke 全部通过。

## 测试方式

- Java 26 Gradle 全量测试与指定 `--rerun-tasks` 故障边界测试。
- PostgreSQL Testcontainers：预算恢复、重复终态、并发任务和租约恢复。
- 可控 stalled SSE Provider：超时、取消、permit 释放、Retry-After。
- Web Vitest、TypeScript build、OpenAPI 生成和 contract check。
- Quant/Proxy pytest、mypy、ruff。
- Kustomize 渲染、镜像、Argo CD、Pod/日志/API/数据库真实验证。

## 状态

- 2026-07-22：已批准 ADR-033。
- 2026-07-22：代码改造和离线生产候选验收完成：
  - AI 调用终态、实际用量和预算释放已收敛到单个 PostgreSQL 事务；孤儿恢复幂等释放预留。
  - Worker 取消令牌可传播到 AI SSE 与交易所提交线程；绝对 deadline、有界 Provider 队列和运行指标已落地。
  - Worker lease/control/SSE Scheduler 已隔离，完成提交竞态已加状态门限。
  - AI 执行策略、流收集、工作流 checkpoint/graph、运行执行资源和 Workspace 报表查询已拆分。
  - 控制面 93 条路径、109 个 Controller 操作均有具体响应与 Problem；19 个真实请求体对齐 Controller；OpenAPI 与手写 TypeScript 类型双向校验响应模型、字段集合和可选性。
  - 8 个无状态 Deployment 均为 `replicas: 1` 和 `Recreate`，无 HPA。
- 验证：Java 244 passed / 20 Testcontainers skipped（本机无 Docker）；Web 20 passed；Quant 16 passed；Proxy 29 passed；Browser 15 passed；两个 OpenAPI、Kustomize 和 kubectl client dry-run 通过。
- 待完成：由 GitHub CI 实跑 20 个 PostgreSQL Testcontainers 用例；经明确生产写入授权后推送 GitOps、观察 Argo CD 同步并执行 Pod/API/数据库 smoke。完成前不归档本任务。
