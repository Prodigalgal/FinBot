# 当前任务

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
