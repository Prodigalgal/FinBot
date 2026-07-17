# 当前任务

## S3：统一运行时配置控制面

- 状态：本轮 Provider 与 Firecrawl 可靠性修复待生产发布验证（2026-07-17）。
- 已完成：Provider 创建与模型创建解耦；新增未保存参数测活和 `/models` 探测导入；移除手填模型名；默认厂商展示名迁移；Firecrawl 网络异常三次换连接重试；代理候选窗口覆盖完整订阅池。
- 本地门禁：Java `clean test bootJar`、Quant 16 项、Proxy 21 项、Web 13 项、OpenAPI contract check 与 Web build 全部通过。
- 待验证：Core/Proxy CI、Liquibase 034、ArgoCD 单副本 rollout、Provider 真实测活、Firecrawl 健康节点与在线信源测试。
- 详情：[`in-progress/TASK-20260716-runtime-configuration-control-plane.md`](./in-progress/TASK-20260716-runtime-configuration-control-plane.md)。

## S3：压缩后分裂的双环境研究工作流

- 状态：进行中（2026-07-15）。
- 目标：共享一次证据采集/清洗/压缩，在不可变快照后分裂为实盘研究与模拟自动交易两个独立工作流。
- 隔离：所有交易所的 `LIVE/TESTNET/DEMO` 在行情、artifact、预测、账户和交易数据上硬隔离；产品模型保持共享简单。
- 已完成：压缩后并行双分支、分支独立工作流选择、环境化行情/预测/风控/OMS 外键、交易所能力矩阵、六策略量化注册表、实时双流事件面板、实盘 shadow PnL。
- 待完成：生产 030 迁移、真实双分支 smoke、CI/GitOps 发布验收。
- 规格：[`../docs/requirements/32-segmented-dual-environment-research.md`](../docs/requirements/32-segmented-dual-environment-research.md)。
- 决策：[`../docs/decisions/016-segmented-research-and-exchange-environments.md`](../docs/decisions/016-segmented-research-and-exchange-environments.md)。

## S3：Java Breaking Migration 生产收口

- 状态：进行中。
- 目标：完成 Java/PostgreSQL 单一生产运行时、真实研究闭环、单副本发布验证和迁移资源清场。
- 详情：[`in-progress/TASK-20260713-java-breaking-migration.md`](./in-progress/TASK-20260713-java-breaking-migration.md)。
- 验收：[`../docs/migrations/010-java-breaking-exec-plan.md`](../docs/migrations/010-java-breaking-exec-plan.md)。

## S3：Monorepo 目录与发布路径整理

- 状态：进行中（2026-07-14）。
- 目标：按 `apps / services / platform / contracts / docs / tasks` 明确所有权，重写 README 和文档索引，并保持 CI/CD 与生产行为不退化。
- 非目标：不修改 API、数据库 Schema、K8S 资源名、镜像名、Secret key 或交易策略。
- 影响：仓库路径、Docker build context、GitHub Actions、GitOps 同步源和文档链接。
- 验收：见 [`../docs/decisions/013-monorepo-layout.md`](../docs/decisions/013-monorepo-layout.md)。

## 既有专案

- P0-P1 工程质量收口：[`in-progress/P0-P1-quality-closure.md`](./in-progress/P0-P1-quality-closure.md)。
- P3 产品研究工作台：[`in-progress/P3-product-research-workspace.md`](./in-progress/P3-product-research-workspace.md)。
- PostgreSQL + SSE breaking migration：[`in-progress/TASK-20260713-postgresql-sse.md`](./in-progress/TASK-20260713-postgresql-sse.md)。

旧 Python 时代的完整任务流水账已归档到 [`done/legacy-python-project-log.md`](./done/legacy-python-project-log.md)，不再作为当前运行入口。
