# ADR-014 可配置模型兜底与杠杆上限语义

## 状态

Accepted

## 决策

### 模型绑定

一个 LLM 节点包含：

- 必填 `primary`：`providerProfileId`、`modelName`、`reasoningEffort`。
- 可选 `fallback`：`providerProfileId`、`modelName`、`reasoningEffort`。
- 节点级 `retryPolicy`、`timeoutSeconds`、`maximumOutputTokens` 对每个绑定分别生效。

执行器先耗尽 `primary` 重试，再在配置完整时耗尽 `fallback` 重试。两者均失败后才交给节点/工作流失败策略。Gemini、MiMo、Sub2API 或其他 provider 在模型绑定中地位相同；代码不得按厂商名隐式选择兜底。

失败调用和成功调用都写入 AI invocation 审计，以实际 provider、model、reasoning、attempt、错误码、token 与费用为准。内部推理原文不落库、不返回前端。

### 杠杆

杠杆使用三个不同概念：

- `preferredLeverage`：用户希望本策略使用的杠杆。
- `maximumLeverage`：当前风控策略允许的上限。
- `venueMaximumLeverage`：交易所对具体合约公布的硬上限。

风险引擎不会把 `venueMaximumLeverage` 当成目标值。最终实际杠杆以 `preferredLeverage` 为起点，并受到风险预算、止损距离、手续费/滑点/强平缓冲、`maximumLeverage` 与 `venueMaximumLeverage` 的共同限制。系统不再施加 100x 的任意限制，但仍拒绝非正数与超出明确技术边界的脏数据。

## 理由

- 模型兜底是运行可靠性能力，不应绑定到某一家供应商或某个具体模型。
- 主/兜底统一契约使工作流编辑、审计、成本控制和新增 provider 无需分支逻辑。
- 交易所最大杠杆是能力边界，不是风险偏好；混用会导致系统自动选择不必要的高杠杆。
- 明确三种杠杆能让 UI、风控、订单和历史审计展示同一语义。

## 兼容与迁移

- 现有节点没有 fallback 字段时按“仅主模型”执行，行为不变。
- 现有风险策略迁移时 `preferredLeverage` 默认等于当前 `maximumLeverage`，随后可由管理员调低；数据库约束同步移除 100x 上限。
- 已发布工作流版本保持不可变；增强后的内置主工作流以新版本发布，旧版本归档但保留历史运行引用。
