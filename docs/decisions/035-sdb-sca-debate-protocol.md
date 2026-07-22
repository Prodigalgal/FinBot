# ADR-035：SDB-SCA 双盲同时辩论协议

- 状态：Accepted
- 日期：2026-07-22

## 背景

现有辩论在同一拓扑层内并行，但后续层仍可看到前层结果，终局由单一 Chair 模型生成
`CHAIR_VERDICT`。这会保留先发锚定、后发信息优势和裁判模型偏差，也无法证明运行恢复后
同阶段信息仍然隔离。

## 决策

1. 引入版本化 `DebateProtocol`，新主工作流使用 `SDB_SCA_V1`；旧协议标记为
   `LEGACY_CHAIR_V1`，仅保留历史读取和在途恢复。
2. 新增独立 `DebateProtocolStore`，持久化 phase、task、artifact、candidate、ballot 和
   decision；不继续膨胀通用 `WorkflowExecutionStore`。
3. 每个阶段采用 snapshot -> sealed tasks -> transactional reveal -> next phase 的状态机。
   barrier 通过数据库 CAS 推进，阶段内产物在揭示前不可被提示词装配器读取。
4. 匿名候选映射持久化；模型输入不得包含 node、role、provider、model、message 等身份字段。
5. 席位使用稳定 `logicalRoleKey`。多个模型可复制同一逻辑角色，但该角色总权重固定为 1。
6. 新增 `SOCIAL_CHOICE` 节点与 `CONSENSUS_RESULT` 输出。终局使用角色归一后的
   winning-votes Schulze；唯一严格胜者是唯一可执行成功态。
7. 每个席位提交正序和逆序匿名选票。两种方向聚合结果不一致时标记
   `ORDER_SENSITIVE`，并投影为 `UNCERTAIN`。
8. 方向概率使用角色内均值再跨角色均值；价格使用角色内中位数再跨角色中位数。
   LLM 可生成解释文本，但不能覆盖数学结果、状态或数值。
9. 任务 ID 和输入哈希确定性生成。同 ID 同哈希幂等；同 ID 不同哈希是显式审计冲突。
10. `CONSENSUS_RESULT` 是新流程事实来源；旧下游在迁移期可读取 `CHAIR_VERDICT` 作为
    legacy fallback，但禁止把 fallback 用于 SDB-SCA 运行。
11. 提案、评审和修正的 canonical artifact 在封存前必须经过身份泄漏守卫；命中任一已知
    node、role、provider 或 model 标识时按结构错误重试，不能依赖提示词自律维持双盲。
12. SDB-SCA 只有非 `UNCERTAIN` 的严格共识可进入执行 AI；低 quorum、并列、顺序敏感和
    无严格胜者直接形成 `WATCH / NO_ACTION`，不允许执行模型重新创造方向。

## 取舍

- 相比顺序辩论，请求数和数据库记录数增加，但换取可证明的信息对称和恢复一致性。
- 全矩阵评审只在候选数不超过 6 时启用；更大规模使用确定性均衡分配，控制成本。
- 单副本 Java Worker 与 PostgreSQL 事务足以满足当前部署约束，不引入 Redis/MQ。
- Schulze 能处理 Condorcet 循环；不使用简单 Borda 作为唯一终局，以减少克隆与排序偏差。

## 迁移

- `061-sdb-sca-protocol.sql` 将数据库变更集总数从 62 增至 65，创建协议配置、运行账本和社会选择表。
- `062-standard-workflow-sdb-sca.sql` 将变更集总数增至 66，并发布默认 SDB-SCA 工作流版本。
- `063-research-forecast-direction-probabilities.sql` 将变更集总数增至 67，持久化可空的
  `UP / SIDEWAYS / DOWN` 概率分布；历史预测保持为空，新 SDB 预测必须完整填写。
- 新运行写入 `CONSENSUS_RESULT`；历史运行保持原样。
- 旧版运行完成后，可删除新建草稿对 `LEGACY_CHAIR_V1` 的选择入口，但不删除历史解析器。

## 回滚

应用回滚时保留新增表和记录，不执行破坏性数据库回滚。默认工作流可重新指向上一个
已验证版本；已开始的 SDB-SCA 运行保持暂停并由兼容版本恢复，不转换为 Chair 裁决。
