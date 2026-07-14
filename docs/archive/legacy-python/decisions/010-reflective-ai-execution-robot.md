# ADR 010：反思型 AI 模拟交易执行机器人

状态：已接受（2026-07-12）

## 背景

多 Agent 研究与辩论会产生方向性候选，但分析模型不应直接构造或修改订单。Gate TestNet 与 Bybit Demo 需要支持无人值守自动提交，同时真实盘必须继续被禁止。

## 决策

- 在 `portfolio_risk` 之后、`ai_governance` 与 `paper_execution` 之前增加 `execution_robot`。
- 执行机器人固定使用 `sub2api / responses / gpt-5.6-sol / xhigh`，不配置降级模型。
- 每轮执行两次独立调用：初审生成候选意见，反思终审主动寻找过度自信、证据冲突、遗漏风险和拒绝理由。
- 只有反思终审返回的既有 `decision_id` 且 `execute=true` 才能继续；缺项、解析失败、模型失败、预算失败均 fail-closed。
- AI 只能批准或拒绝，不能改变方向、数量、价格、杠杆或绕过确定性风险门禁。
- TestNet/Demo 默认不要求人工复核；生产 readiness 要求自动提交时必须启用执行机器人。
- OMS、交易适配器和风险引擎继续只允许 `paper/testnet/demo`，Mainnet/Live 永久拒绝。

## 取舍

- 两次 `gpt-5.6-sol/xhigh` 调用增加延迟和成本，但最终执行属于高风险决策点，优先换取可审计的二次反思。
- 不允许 Sol 降级，避免供应商故障时静默换用较弱模型；代价是故障时本轮不下单。
- 自动批准只移除人工操作瓶颈，不移除组合风险、AI Governance、置信度、名义价值、订单数量、合约映射和重复持仓门禁。

## 回滚

- 将 `execution_robot.enabled=false` 且 `paper_execution.submit_orders=false`，可恢复为只分析、不提交模式。
- 将 `paper_execution.require_human_review=true`，可恢复人工批准模式。
