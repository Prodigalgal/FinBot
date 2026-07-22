# ADR-033：单副本模块化单体与进程内 AI 执行控制面

## 状态

Accepted，2026-07-22。

## 背景

FinBot 当前由 Java 26 / Spring Boot 4.1 主系统、React Web、Python Quant、Browser Worker、
SearXNG 与隔离代理服务组成。主系统已经使用 PostgreSQL 保存后台任务、工作流检查点、
AI 调用、预算、事件和交易账本，并通过 `FOR UPDATE SKIP LOCKED` 认领持久任务。

近期审计确认，系统的主要风险不是跨服务吞吐，而是单进程内部的执行正确性：AI 调用终态
与预算释放不是同一事务、取消不能可靠传播到实际 HTTP 流、容量等待可能突破工作流截止时间、
Worker 心跳与 SSE 共用调度器，以及公开 OpenAPI 仍包含大量通用响应模型。

当前生产明确采用单副本并允许发布期间短时中断。增加 Redis、RabbitMQ 或其他消息系统会
复制 PostgreSQL 已经承担的队列和状态职责，并引入双写、消息幂等、死信、Broker 运维和新的
故障恢复边界，不能直接解决上述问题。

## 决策

1. Java Backend、Web、Quant、Browser Worker、SearXNG 和各代理服务保持单副本部署；不配置 HPA。
2. 无状态 Deployment 使用 `Recreate`，确保发布期间不存在两个 Backend 或 Worker 实例并行运行。
3. 不引入 Redis、MQ、Outbox、分布式锁或分布式限流。PostgreSQL 是唯一业务事实源和持久任务队列。
4. Java Backend 保持一个部署单元，内部继续使用 `domain -> application -> infrastructure -> bootstrap`
   模块边界；大型类按应用职责拆分，不拆成新的网络服务。
5. AI 准入、并发、RPM/TPM、冷却、重试、fallback、绝对 deadline 和取消由进程内
   `AiExecutionCoordinator` 统一治理。等待队列必须有界，并按真实 Provider 凭据/渠道隔离。
6. AI 调用终态、实际用量累计和预算释放必须在单个 PostgreSQL 事务中完成；启动恢复必须幂等地
   关闭孤儿调用并释放对应预留。
7. Worker lease 是外部副作用的 fencing 边界。实际执行线程、子任务和 HTTP Body 必须共享可传播的
   取消句柄；失去 lease 的执行不得再提交工作流、订单或任务终态。
8. Worker lease heartbeat、Worker 控制循环和 SSE heartbeat 使用独立调度器。虚拟线程只承载已获准的
   阻塞 I/O，不作为容量控制手段。
9. `contracts/finbot-control-plane.openapi.yaml` 是控制面唯一公开契约。所有操作必须声明具体请求、
   成功响应和 Problem 响应；Java API 和 TypeScript 类型保持手工维护，由 CI 校验路径、方法、响应模型、
   字段集合与可选性，不依赖代码生成。
10. SSE 的权威事件仍先写 PostgreSQL。客户端重连使用 `Last-Event-ID` 回放，不依赖进程内通知可靠性。

## 运行结构

```text
Web (1)
  -> Java Backend: API + SSE + Scheduler + Worker (1)
       -> PostgreSQL: queue + checkpoints + events + ledgers (1)
       -> Quant / Browser Worker / SearXNG / Proxy Gateways (各 1)
       -> AI Providers / Gate / Bybit
```

Java 进程内存只保存当前执行句柄、Provider permit、有界等待者、SSE 订阅者和可重建缓存。
这些状态可在进程退出时丢失；启动恢复只能依据 PostgreSQL 权威状态推进。

## 取舍

- 优点：部署组件少、状态边界单一、事务一致性直接、故障恢复可验证，符合当前单副本负载。
- 代价：Backend 不能横向扩容；进程内限流不跨 Pod；单次发布需要短时中断。
- 接受该代价，因为当前明确不要求高可用或零中断，且任务吞吐尚未证明 PostgreSQL 队列是瓶颈。
- 若未来出现多 Backend 副本、独立消费者、跨服务事件订阅或经测量确认的队列瓶颈，必须新增 ADR，
  重新评估 Redis/MQ；不得在现有接口下静默引入第二事实源。

## 验收

- 所有相关 K8S Deployment `replicas: 1` 且更新策略不产生并行 Backend。
- AI 孤儿恢复后不存在对应 `RESERVED` 预算；调用终态与工作流预算账本一致。
- 超时或 lease 丢失能关闭真实 HTTP Body，并在限定时间内释放 Provider permit。
- 容量等待、退避和 HTTP 请求共同受同一个绝对 deadline 约束。
- Worker/SSE 调度线程隔离，已完成任务不会被误报为 lost lease。
- OpenAPI 不再使用通用成功 `JsonObject`；Java、Web 与契约检查通过。
- Java、Web、Quant、Proxy 全量测试和生产 smoke 通过。

## 回滚

- 代码重构按模块提交，可回退到上一生产镜像；Liquibase 只追加可兼容字段、索引和恢复数据变更。
- 若新的进程内 AI 准入器异常，可回滚整个 Backend 镜像，不提供绕过预算或限流的运行开关。
- OpenAPI 生成链可回滚到上一版本契约，但不能关闭 Controller/Web 覆盖检查后继续发布。
