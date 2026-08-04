# ADR-036：全链路 SDB-SCA 使用可复用决策面板内核

- 状态：Accepted
- 日期：2026-08-04

## 背景

当前研究流程已经使用 SDB-SCA，但协议执行仍由研究专用的 `SdbScaDebateExecutionService`
承载，每个 workflow run 只能拥有一个 `debate_session`。交易自动化仍由单个 DRAFT 模型生成计划、单个 REFLECTION
模型修正，存在顺序锚定、同模型自我确认和单点裁决偏差。证据清洗虽有多席位，也没有统一的
barrier、匿名评审和确定性 reducer。

## 决策

1. 从研究专用协议执行器提取应用层 `DecisionPanelEngine`，统一负责 freeze、seal、
   reveal、critique、revision、ballot、reduce 和 recovery。
2. 引入 `DecisionPanelPurpose`：`EVIDENCE`、`RESEARCH`、`PRINCIPAL_REVIEW`、`EXECUTION`。
3. 引入可插拔但确定性的 `DecisionPanelReducer`：事实集合 reducer 与角色归一 Schulze reducer
   共用协议内核，不用 Provider 名称分支。
4. `debate_session` 增加 `panel_key`、`panel_purpose`、`input_hash` 和 CAS `version`，唯一约束从
   `run_id` 改为 `(run_id, panel_key)`；session 及其派生协议 ID 纳入 panel key。历史 session
   的冻结输入无法可靠重建，`input_hash` 保持 `NULL`，不得伪造哈希。
5. 工作流节点增加 `panel_key` 与 `panel_purpose`。节点类型继续表达执行能力，panel 字段表达
   协议归属，避免为每种业务面板复制一套 NodeType。
6. 研究执行改为调用 `DecisionPanelEngine`，现有 SDB-SCA 表和算法原样迁入，不重写 Schulze。
7. `TradeAutomationApplicationService` 停用顺序 DRAFT/REFLECTION 调用，改为执行 EXECUTION
   panel；选中的 canonical plan 再进入现有 `MarginRiskEngine`、`EstimatedTradeEngine` 和 OMS。
8. PRINCIPAL_REVIEW 是独立面板，不设置单一 Chair。它只能对上游共识做确认、收紧或拒绝。
9. `gpt-5.6-sol` 可作为主审/执行的高能力席位，但与其他逻辑角色一起进入匿名协议，不能覆盖 reducer。
10. 所有面板继续使用单副本 Java Worker + PostgreSQL CAS，不引入 Redis/MQ。

## 边界

- Panel engine 只处理 AI 协议和 canonical artifacts，不直接访问交易所或提交订单。
- Reducer 不解析 Provider 私有字段；模型协议差异留在现有 AI Provider adapter。
- 自动执行只有在研究、主审、执行三层均严格成功且硬风控通过时才可继续。
- 历史 `CHAIR_VERDICT`、DRAFT、REFLECTION 记录永久可读；新默认版本不再生成这些 legacy 结果。

## 迁移顺序

1. 多 panel session 数据模型、ID 和 Store 兼容迁移。
2. 提取 RESEARCH panel，证明与当前 SDB-SCA 行为等价。
3. 增加 PRINCIPAL_REVIEW panel 与下游约束投影。
4. 增加 EXECUTION panel，切换交易自动化并保留硬风控/OMS。
5. 将证据清洗/压缩接入 EVIDENCE panel，发布新的全量默认工作流。
6. 完成 UI、OpenAPI、运行态指标、恢复测试和生产 TestNet 验收后，隐藏 legacy 创建入口。

## 回滚

保留新增 panel 账本和历史记录。默认工作流可切回上一个已验证版本；已开始的新 panel 不转换为
legacy Chair 或顺序执行，只能恢复、失败关闭或由管理员取消。
