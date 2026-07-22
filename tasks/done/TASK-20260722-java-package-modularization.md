# TASK-20260722：Java package 模块化整理

## 目标

落实 ADR-034，在不改变公开 API、数据库结构和业务行为的前提下，把 Java Backend 按业务域和职责整理为可维护、可搜索、可持续约束的 package 结构。

## 范围

- Bootstrap API 的 `controller`、`dto` 分离。
- Application 的 `service`、`dto`、`port.in`、`port.out` 分离。
- Infrastructure 的 `persistence`、`client`、`adapter` 分离。
- 测试 package、Spring 扫描、imports 与构造器注入同步迁移。
- 增加 package 布局和模块依赖架构守卫。

## 非目标

- 不拆分新微服务，不引入 Redis 或 MQ。
- 不改变 OpenAPI 路径、响应语义、数据库字段或业务状态机。
- 不引入代码生成、重复 DTO、空 Mapper 或仅转发的 Service。
- 不重做前端 UI。

## 影响文件

- `services/backend/finbot-domain`
- `services/backend/finbot-application`
- `services/backend/finbot-infrastructure`
- `services/backend/finbot-bootstrap`
- `services/backend/finbot-migration`
- `docs/decisions/034-feature-first-java-package-architecture.md`

## 验收标准

1. 所有职责类位于 ADR-034 约定 package，源码目录与 package 一致。
2. Controller 仅位于 `api.<feature>.controller`，HTTP Request/Response 仅位于 `api.<feature>.dto`。
3. Application Service、UseCase、Repository/Store/Gateway 和 DTO 不再平铺在 feature 根 package。
4. Infrastructure JDBC 与外部客户端实现分别进入 `persistence`、`client` 或明确 `adapter`。
5. 架构守卫可阻止跨层依赖和新平铺职责类。
6. Java 全量测试、OpenAPI/Web 契约、GitHub CI、GitOps 和生产 smoke 全部通过。

## 测试方式

- Java 26 Gradle `clean test` 与架构守卫测试。
- Spring context、Controller、安全、Worker 和 PostgreSQL Testcontainers 回归。
- OpenAPI/Web contract check 与生产构建。
- GitHub Actions、Argo CD、Pod/日志、登录与关键 API smoke。

## 状态

- 2026-07-22：ADR-034 已批准，开始盘点与迁移。
- 2026-07-22：完成 Application、Infrastructure、Bootstrap API、配置、后台运行时和安全入口的 feature-first 职责分包；测试 package 同步迁移。
- 2026-07-22：增加 `PackageArchitectureTest`，覆盖 path/package 一致性、层依赖方向、职责落位、Bootstrap 子包和禁止生成代码。
- 2026-07-22：Grok 执行 Bootstrap 分包与架构守卫初稿，MiMo 独立复核，主 Agent 修正 `AuthenticationPolicy` 分类及 `*Policy` 守卫漏检；两套 CLI 最终复核均无阻断问题。
- 2026-07-22：本地 Java 全量测试通过；Web 契约检查通过（93 paths / 109 controller operations）；Vitest 20/20 通过；生产构建通过。
- 2026-07-22：提交 `001998c` 推送至 `main`；GitHub Actions run `29892673979` 全部通过，包含真实 PostgreSQL/Testcontainers、Java/Python/Web、Playwright、Kustomize、4 个 ARM64 镜像、Trivy 和 Cosign。
- 2026-07-22：GitOps revision `5e66734` 已同步；四个核心镜像运行 `sha-001998c040f2d37bbbc72843b07bf844cd7dc307`，Argo CD 为 `Synced/Healthy`，所有生产 Pod `Ready` 且 0 重启。
- 2026-07-22：生产 root/live/ready 为 HTTP 200，匿名敏感 API 为 401；认证后 13 页面桌面/移动端、CSRF 409 业务冲突链路与 Operations SSE smoke 通过。任务完成。
