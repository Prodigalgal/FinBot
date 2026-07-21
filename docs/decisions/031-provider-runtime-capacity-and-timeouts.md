# ADR 031：AI Provider 容量与慢请求超时

- 状态：Accepted
- 日期：2026-07-21

## 背景

不同 AI Provider 的负载、额度和响应时长差异明显。固定的全局并发与单一超时既会让慢厂商过早失败，也会在任务突发时放大上游、数据库和 Worker 压力。配置需要由管理员在运行时调整，并对工作流补全与 AI Web Search 一致生效。

## 决策

- `ai_provider_profile` 是 Provider 容量的唯一配置真相：`maximum_concurrent_requests` 默认 5，范围 1-32；`acquire_timeout_seconds` 默认 1800，范围 5-7200；`request_timeout_seconds` 范围扩大到 5-3600。
- 容量排队与实际请求分开计时。请求取得 Permit 后才发出 `AiStreamStarted`，此后开始计算单次请求超时。
- 所有运行态 Provider 调用共享按 `provider_profile_id` 隔离的公平容量门；当前覆盖工作流 AI Completion 与 AI Web Search。
- 容量门使用 Provider `version` 防止旧请求把新配置覆盖回旧值。配置上调立即允许新请求进入；配置下调不取消进行中请求，只阻止新的超额请求。
- 工作流节点和最终执行阶段允许最长 3600 秒的单次请求；工作流 `maximum_duration_seconds` 仍是业务流程的上游总时限。
- 删除 `FINBOT_AI_MAX_CONCURRENT_PER_PROVIDER` 与 `FINBOT_AI_PROVIDER_ACQUIRE_TIMEOUT`，避免数据库和部署环境出现双重配置真相。

## 取舍

- 单实例内存容量门符合当前单副本部署约束；若未来横向扩容 Backend，必须改为分布式租约或在统一 AI Gateway 实施全局容量控制。
- 下调并发不强制终止进行中的外部请求，避免产生未知提交结果和不完整审计；因此容量收敛是渐进的。
- 更长等待会增加任务端到端时长，管理员需要同步设置工作流总时限，并依靠 `AI_PROVIDER_CAPACITY_TIMEOUT` 与 `AI_INVOCATION_TIMEOUT` 区分排队失败和请求执行失败。

## 回滚

回滚应用镜像前先把 Provider 请求超时降回 1800 秒以内，再回滚 Liquibase 056；数据库回滚会删除容量字段并恢复旧约束。旧版全局环境变量仅作为旧镜像回滚时的临时配置，不再属于现行契约。
