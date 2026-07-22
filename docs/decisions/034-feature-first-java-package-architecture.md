# ADR-034：Feature-first Java package 架构

## 状态

Accepted，2026-07-22。

## 背景

FinBot Backend 已按 `domain -> application -> infrastructure -> bootstrap` 拆分 Gradle 模块，模块依赖方向正确，
但业务域内部仍将应用服务、DTO、入站用例、出站端口、持久化实现和 HTTP DTO 平铺在同一 package。
随着研究、采集、工作流、AI、交易与运维能力增长，同名职责难以快速定位，也缺少自动化结构守卫。

## 决策

1. 保留现有 Gradle 模块和依赖方向，不新增网络服务或空洞模块。
2. package 先按业务域聚合，再按职责分层；禁止建立全局巨型 `dto`、`service`、`repository` package。
3. `domain.<feature>` 保存实体、值对象、领域状态和纯领域规则，不依赖 Spring、JDBC、HTTP 或应用 DTO。
4. `application.<feature>.service` 保存用例编排和应用级策略。
5. `application.<feature>.dto` 保存 Command、Result、View、Criteria、Snapshot 等应用边界数据。
6. `application.<feature>.port.in` 保存由 API、Scheduler、Worker 调用的 UseCase 接口。
7. `application.<feature>.port.out` 保存 Repository、Store、Gateway、Publisher、Resolver 等外部能力端口。
8. `infrastructure.<feature>.persistence` 保存 JDBC Repository/Store、数据库映射与持久化 codec。
9. `infrastructure.<feature>.client` 保存交易所、AI、Quant、代理等外部 HTTP 客户端及协议实现。
10. `infrastructure.<feature>.adapter` 仅用于需要组合多个技术实现的适配器；不作为无法分类代码的兜底目录。
11. `api.<feature>.controller` 与 `api.<feature>.dto` 分离 HTTP 入口和传输模型；Controller 不承载业务规则。
12. 不依赖 API 客户端或 DTO 代码生成；OpenAPI、Java 与手写 TypeScript 通过 CI 契约检查保持一致。
13. Bootstrap 配置按 `configuration.properties` 与 `configuration.wiring` 分离配置值和 Bean 装配。
14. Bootstrap 后台执行按 `operations.handler` 与 `operations.runtime` 分离任务处理器和常驻运行时。
15. Bootstrap 安全入口按 `security.principal`、`security.filter`、`security.configuration` 分离身份、过滤链和安全装配。

## 约束

- Domain 不得依赖 Application、Infrastructure、Bootstrap。
- Application 不得依赖 Infrastructure、Bootstrap。
- Infrastructure 只实现 Application 出站端口，不向 Application 泄漏 JDBC/HTTP 类型。
- API DTO 不进入 Domain；Application DTO 不直接承担 HTTP 注解或序列化兼容职责。
- 新增 `Service`、`UseCase`、`Repository`、`Store`、`Gateway`、`Controller`、`Request`、`Response` 时必须进入对应职责 package。
- `PackageArchitectureTest` 必须持续检查源码路径与 package 一致性、层依赖方向、职责落位及 `generated` package 禁令。

## 迁移策略

1. 先迁移 Bootstrap API 与 Infrastructure，实现物理目录和 package 一致。
2. 再迁移 Application 的 `service`、`dto`、`port.in`、`port.out`，编译驱动补齐原同包隐式引用。
3. Domain 保持 feature-first，仅修正跨层误放类型。
4. 测试跟随被测 package 迁移；增加架构守卫，禁止新代码回到平铺 package。
5. 每一阶段必须通过 Java 全量测试；最终经 GitHub CI、GitOps 和生产 smoke 验证。

## 取舍

- 优点：业务能力仍保持高内聚，同时可以按职责快速定位；端口和实现的边界可由构建与测试持续约束。
- 代价：本次迁移会产生较多文件移动和 import 变化，但不改变 API、数据库或运行行为。
- 不采用全局 `controller/service/repository/domain` 分层，因为它会把同一业务能力拆散，并放大跨域耦合。

## 回滚

- 本次是无状态源码重构，不包含数据库变更；可整体回退对应提交和镜像。
- 若某一业务域迁移导致行为变化，应回退该业务域迁移，不允许通过兼容转发 package 长期保留双结构。
