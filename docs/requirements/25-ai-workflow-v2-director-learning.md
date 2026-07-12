# AI Workflow v2：Director、领域工作流与学习闭环

## 实施状态

P0-P2 已于 2026-07-12 完成并通过验收。实现证据见 `docs/reports/25-ai-workflow-v2-acceptance.md`。

## 目标

- 将当前单一 Council DAG 升级为可表达研究组织的领域工作流，而不是只增加角色和轮数。
- 保留 v1 Council 的真实多轮辩论、角色级模型绑定、版本发布、成本预估和 advisory-only 安全边界。
- 支持按问题和产品动态选择分析团队、补证据、辩论、风险、人工复核和结果学习路径。
- 提供快速扫描、标准研究、深度投委会、事件冲击、持仓复核五套可直接启用的模板。
- 将工作流面板作为主要配置入口：用户先编写或选择角色，再在画布中自由组合节点、轮次、方向、信息流和节点级模型参数。

## 用户操作模型

- 用户可从角色库新建角色，配置名称、职责、系统提示词、用户提示词模板和默认输出契约。
- 用户在画布中任意添加角色节点、控制节点和子流程节点，并用有向边决定执行方向。
- 工作流级配置包含默认轮数、最小/最大轮数、停止条件、成本档位和失败策略。
- 节点检查器直接编辑角色和 LLM 绑定：模型服务/凭据配置、协议、模型名称、思考等级、备用服务、输出上限和重试策略。
- 边检查器编辑执行条件、信息是否传递、上下文形态、激活组和安全循环上限。
- 角色高级表单继续兼容，但工作流画布是新建、调试、预估、发布和运行的主入口。
- 原始 API key 只保存在 AI site/credential profile 中；节点和工作流版本只保存 `site_id` 引用，禁止复制、导出或落库明文 key。

## 当前问题

- 当前运行配置只有一个产品研究委员会，实际为 4 个分析角色、3 个阶段和 1 个 Chair。
- v1 节点只有 `input`、`agent`、`chair`，无法表达条件、补证据、确定性计算、人工复核或子流程。
- 所有启用角色默认参与每一轮；阶段数不足时后续轮次重复最后一个阶段。
- 历史消息默认广播给后续轮次，角色和轮数扩大后会造成上下文污染与 Token 线性增长。
- `moderated` 已进入配置枚举但执行器未实现；Council 内部没有节点级 checkpoint/resume。

## 范围

### P0：Workflow v2 契约与执行

- v1/v2 双版本解析和执行；旧模板、旧版本快照和既有 API payload 保持有效。
- v2 节点类型：`input`、`router`、`deterministic`、`agent`、`gate`、`subflow`、`human_review`、`aggregator`、`chair`。
- v2 条件边、安全循环、`all/any` 激活策略、角色按 phase 参与、节点重试与确定性终止。
- 执行图和上下文图分离；每个 Agent 可限制历史轮次、消息数量、来源节点和内容形态。
- 条件只使用受控 DSL；不得执行 Python、JavaScript、Shell、任意 HTTP 或用户提供表达式。

### P1：Director、持久化与默认模板

- Research Director 根据触发类型、query、产品类型、证据状态和成本档位生成执行计划。
- Task Ledger 保存目标、事实、假设、缺口、选择模板和计划修订；Progress Ledger 保存步骤、停滞、错误和重规划原因。
- 节点完成后持久化 checkpoint；相同 run/node/iteration 幂等，Worker 重启后可从最近成功节点恢复。
- 节点失败按显式 retry policy 重试；达到重试、步骤、循环、Token、成本或时间上限后确定性终止。
- 内置五套模板和 `quick`、`standard`、`deep` 三档成本策略。

### P2：结果学习

- 基于消息完成度、证据覆盖、Chair 采纳、人工复核和成熟 outcome 计算角色效果评分。
- 运行结束生成结构化 reflection：有效论点、主要错误、缺失证据、需要调整的角色或流程。
- 长期记忆按 template、role、产品、市场类型和主题打标签，只选择性注入相关节点。
- 学习结果只能影响后续上下文与 Director 排序，不得自动改 Prompt、发布 Workflow 或放宽交易门禁。

## Workflow v2 契约

### Node

- `node_id`：模板内唯一稳定标识。
- `node_type`：领域节点类型。
- `role_id`：`agent`、LLM `aggregator`、`chair` 使用；其他节点为空。
- `operation`：受控操作 ID，例如 `evidence_quality`、`market_snapshot`、`template_router`、`research_gap_gate`。
- `phase_ids`：空表示所有 phase；非空表示只在指定 phase 参与。
- `config`：节点类型对应的结构化参数，不允许任意代码。
- `context_policy`：`upstream`、`selected`、`latest`、`claims_only`、`summary`、`none`。
- `retry_policy`：最大尝试次数和固定退避秒数。
- LLM 角色节点通过 `role_id` 关联角色配置；工作流节点检查器可以原地修改该角色的 `site_id`、`protocol`、`model`、`reasoning_effort`、备用站点和 Prompt。
- `position`：仅用于编辑器布局，不参与研究事实。

### Edge

- `condition`：字段路径、受控操作符和 JSON 值组成的条件；空表示无条件。
- `activation_group` 与 `activation_mode=all|any`：控制汇聚节点何时执行。
- `context_mode`：决定该边消息是否进入目标节点上下文。
- `loop=true` 的边必须带条件和 `max_traversals`，并受工作流全局步骤/成本上限约束。

### Condition DSL

- 字段只可读取 Workflow state 的公开结构化字段。
- 操作符仅支持 `exists`、`eq`、`ne`、`in`、`not_in`、`gt`、`gte`、`lt`、`lte`、`contains`、`truthy`、`falsy`。
- 条件求值失败按 false 处理并写入审计，不得抛出未处理异常或执行动态代码。

## 默认组织模板

- `quick_market_scan`：行情快照、新闻摘要、技术分析、风险检查、快速合成。
- `standard_product_research`：研究路由、多维分析、证据审计、Bull/Bear、风险、Chair。
- `deep_investment_committee`：补证据循环、情景分析、交叉质询、组合风险、人工复核。
- `event_impact_analysis`：事件可信度、影响链、市场确认、反事实和失效条件。
- `position_review`：原建议回放、当前行情、风险变化、继续/调整/退出建议。

## 状态与恢复

- Workflow run：`planned -> running -> waiting_human|completed|partial|failed|cancelled`。
- Node checkpoint：`pending -> running -> completed|skipped|waiting_human|failed`。
- 同一 `workflow_run_id + node_id + phase_id + iteration` 只能有一个最终 checkpoint。
- 恢复只重跑非最终节点；已完成节点输出从 checkpoint 读取，不重复调用外部 AI。
- Human review 默认只在深度模板阻塞；其他模板可生成非阻塞复核建议，但不得自动模拟执行。

## API 与 UI

- AI 配置 API 返回 workflow schema version、内置模板元数据、Director/learning 摘要，不返回密钥或隐藏推理。
- 工作流编辑器按“输入、控制、分析、审计、人工、输出”展示节点库，并区分执行边和上下文策略。
- 画布顶部集中管理模板、运行深度、轮次策略、成本预估、草稿/发布/回滚和测试运行；右侧检查器承担节点、角色、LLM、Prompt、边条件与信息流配置。
- AI site 可配置相同厂商的多组 credential profile；节点只选择 profile，不显示完整 key。思考等级使用统一枚举并由 provider adapter 映射为对应协议参数。
- 运行详情展示计划、节点状态、重试、循环次数、成本、上下文来源、reflection 和角色评分。
- 发布前必须通过 schema、可达性、安全循环、预算、角色映射和上下文策略校验。

## 非目标与安全边界

- 不引入任意脚本、任意 HTTP、外部 SaaS 写入或自由工具调用。
- 不允许 Workflow 节点直接下单、撤单、转账、修改杠杆或访问 Mainnet 私有接口。
- 不展示隐藏 chain-of-thought；只保存结构化结论、证据、质询、修订和审计字段。
- 不在本阶段替换 SQLite；K8S 继续采用单 Pod 双容器、共享 PVC、`replicas=1` 和 `Recreate`。

## 验收标准

- v1 三轮 Council 结果、消息数、reply 引用和已发布版本继续通过原测试。
- v2 条件分支、安全循环、上下文过滤、phase participation、重试和 checkpoint/resume 有成功/失败/边界测试。
- 用户可只在工作流面板完成角色创建、轮次设定、节点级 provider/credential/model/reasoning/Prompt 配置和信息流连线，并通过发布前校验。
- Director 能为五类场景选择模板和成本档位，计划与重规划原因可审计。
- 五套模板均能通过保存、预估、节点测试和 dry-run；至少标准模板完成真实 AI smoke。
- 角色评分和记忆只使用可追溯数据，选择性注入有数量和 Token 上限。
- 全量 Python 测试、compileall、前端构建、API smoke、桌面/移动浏览器 smoke、Web/Worker 常驻恢复通过。
