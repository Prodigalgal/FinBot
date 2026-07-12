# P2.1 AI 调度工作流

## 目标

- 将 Council 角色配置升级为可视化工作流，用户可以添加、删除、拖动节点并通过连线定义执行依赖。
- 每个 Agent/Chair 节点可独立配置 AI 站点、协议、模型、备用站点、角色目标和提示词。
- 连线必须参与真实运行：决定同一轮内的拓扑执行顺序、下游可见消息和直接回复引用。
- 保留现有多轮 phase、quorum、预算门禁、A/B 实验和 advisory-only 策略。

## 范围

- `finbot/council/models.py`
- `finbot/council/orchestrator.py`
- `finbot/autonomous/ai_debate.py`
- `finbot/config/ai_sites.py`
- `config/ai_sites.example.json`
- `web-ui/src/CouncilWorkflowPanel.tsx`
- `web-ui/src/types.ts`
- `web-ui/src/App.tsx`
- Council、AI config、Web API 和前端构建相关测试

## 非目标

- 不允许工作流节点调用交易执行、下单、撤单、转账或账户私有 API。
- 不引入任意脚本节点、循环边、条件表达式或外部分布式工作流引擎。
- 不移除现有“AI 调度小组”的表单式高级配置入口。
- 不把 workflow position 等展示字段写入辩论事实或建议证据。

## 契约

- `CouncilTemplate.workflow.version` 当前固定为 `1`。
- 节点类型为 `input`、`agent`、`chair`；Agent 节点通过 `role_id` 引用 `roles`，Chair 节点引用 `chair.role_id`。
- 每个模板必须且只能有一个 input 和一个 chair；每个 role 必须且只能映射一个 Agent 节点。
- 工作流必须为 DAG，所有 Agent 必须从 input 可达且能够到达 chair；禁止自环、重复边、悬空端点、指向 input 或从 chair 发出的边。
- 缺少 `workflow` 的旧模板在读取时自动生成兼容图，并在下一次保存时物化为 v1 契约。

## 执行语义

- 每个 phase 按工作流拓扑层执行；`parallel` 在同层并行，`round_robin` 在同层按 role order 轮流。
- 已完成的历史轮次对下一轮所有角色可见，以保留交叉质询与立场修订能力。
- 当前轮次只把上游节点已完成消息传给下游节点；`reply_to_message_ids` 同时保留历史轮次和当前上游引用。
- Chair 在所有辩论 phase 完成后执行；直接回复引用取自连入 Chair 的 Agent 节点，综合输入仍包含完整辩论记录。
- 禁用 Agent 不执行，但图可达性保留；活跃节点根据可达关系重新计算依赖层。

## 验收标准

- 用户可以从节点库添加 Agent，删除 Agent，拖动节点位置，并从输出端口连到下一个节点。
- 选中 Agent/Chair 后可编辑对应 LLM 和提示词；修改只写入现有 role/chair 配置。
- 无效图在前端即时显示具体错误，后端保存时再次拒绝，不能静默落盘。
- 默认产品委员会形成“研究上下文 -> 三个研究角色 -> 风险控制员 -> 主席仲裁员”的图。
- 测试证明拓扑层、同轮上游上下文、跨轮历史、旧模板迁移和环检测行为。
- `python -m compileall finbot`、`python -m unittest discover -s tests`、`npm run build` 通过。
- 桌面和 390px 窄屏可操作；画布、面板和文字没有页面级横向溢出。

