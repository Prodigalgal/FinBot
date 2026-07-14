# ADR-008：Workflow v2 领域引擎与分层研究组织

## 状态

已接受，2026-07-12。

## 背景

FinBot v1 Council 已实现真实多轮辩论、DAG 拓扑和角色级 LLM 配置，但它仍是一个“所有角色按轮发言，最后 Chair 合成”的局部模型。直接增加角色或轮数会线性放大调用、历史上下文和失败面，无法表达研究机构中的任务拆解、证据补充、条件路由、风险委员会、人工门禁和结果学习。

TradingAgents、FinRobot、FinCon、Magentic-One、AutoGen GraphFlow 和 CrewAI 的共同经验是：复杂度应来自分层团队、显式状态和受控路由，而不是一个无限扩大的群聊。

## 决策

- 在现有 `finbot.council` 内实现小型领域工作流引擎，不引入 LangGraph、AutoGen、CrewAI 或外部分布式引擎依赖。
- `workflow.version=1` 保持原模型和执行器；新增 version 2 契约，发布版本保存完整快照。
- v2 将执行图与上下文策略分离：边决定执行可达性，`context_policy/context_mode` 决定消息可见性。
- 允许显式循环边，但循环必须有受控条件、遍历上限和全局步骤/预算上限；移除 loop 边后的主图必须为 DAG。
- `router`、`deterministic`、`gate`、`subflow` 和 `human_review` 只能调用内置 operation registry，不接受代码或 URL。
- 工作流画布成为角色与调度的主要编辑界面；`roles/chair` 仍是序列化真相源，但节点检查器直接编辑其引用对象，避免 node 和 role 两套配置漂移。
- 节点不保存 API key，只引用 AI site/credential profile；同厂商多 key 通过多个 site profile 表达，版本快照和运行审计只记录 site/model/protocol/reasoning 等非敏感元数据。
- 轮次从单一运行时整数升级为模板 round policy；运行请求可在策略允许范围内覆盖，安全停止条件和预算上限不可被节点 Prompt 覆盖。
- Research Director 使用确定性规则产生初始计划，LLM 只可补充计划说明；安全、预算和模板选择最终由本地策略决定。
- Task/Progress Ledger、node checkpoint、role score 和 selective memory 使用 additive SQLite schema。
- 反思和评分不能自动修改 Prompt、发布模板或解锁模拟/真实交易。

## 关键取舍

- 自研领域引擎增加本项目维护责任，但避免引入大型依赖、外部 checkpoint 格式和与现有 SQLite/Worker 双状态机冲突。
- v1/v2 双栈会暂时增加分支，但比隐式迁移所有已发布工作流更可回滚。
- 把大部分配置集中到画布会增加前端复杂度，但能显著缩短角色、模型、轮次和信息流之间的操作距离；高级表单保留为兼容入口。
- 循环只支持受控边而不是任意图回边，牺牲通用性换取成本可预测和可终止性。
- Human review 作为领域节点进入状态机，但执行仍复用现有 decision review 和模拟执行双重门禁。
- 默认模板增加到五套，但每次运行由 Director 按场景选择，不会全量执行所有角色。

## 数据和兼容

- v1 `CouncilTemplate`、`ai_debate_councils/messages/decisions` 和 API 字段不删除。
- v2 运行额外写入 workflow run、ledger、checkpoint、role score 和 memory 表；旧读模型忽略新增字段。
- 旧 `ai_sites.json` 不强制改写；加载时提供缺失的内置模板，用户发布或保存后才持久化。
- `WorkflowVersionService` 按内容版本校验 v1/v2，rollback 继续复制旧快照生成新版本。

## 回滚

- 设置 `autonomous.workflow_engine_version=1` 或指定 v1 模板可立即回退原 Council。
- 新表为 additive，不删除旧数据；关闭 Director/learning 后不影响 v1 研究和建议链。
- v2 模板发布失败时不修改当前 AI 配置；恢复旧 published version 仍通过现有 rollback 路径。
