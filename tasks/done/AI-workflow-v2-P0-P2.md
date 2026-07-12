# AI Workflow v2 P0-P2 Goal

## 目标

完成 `docs/requirements/25-ai-workflow-v2-director-learning.md` 和 `docs/decisions/008-ai-workflow-v2-domain-engine.md` 定义的 P0-P2，并保持现有研究、Council、人工复核和模拟执行能力零退化。

## P0：契约与引擎

- [x] v1/v2 数据模型与兼容解析。
- [x] 新领域节点、条件 DSL、条件边、激活组和安全循环。
- [x] phase participation、执行图/上下文策略分离。
- [x] v2 引擎、重试、确定性终止和单元测试。

## P1：Director 与可靠性

- [x] Research Director、Task Ledger、Progress Ledger。
- [x] workflow run 和 node checkpoint schema、幂等写入与 resume。
- [x] 失败重试、停滞检测、确定性重规划。
- [x] 快速扫描、标准研究、深度投委会、事件冲击、持仓复核模板。
- [x] quick/standard/deep 成本档位和准确预估。

## P2：学习闭环

- [x] 角色效果评分和 Chair 采纳/证据覆盖指标。
- [x] 结构化 reflection 和来源审计。
- [x] 按角色、产品、市场和主题选择性检索长期记忆。
- [x] learning 不得自动改 Prompt、发布模板或放宽交易门禁。

## API 与 UI

- [x] Workflow schema/模板/运行/学习 API。
- [x] 编辑器节点库、条件/上下文配置、模板信息架构。
- [x] 工作流面板内完成角色创建、Prompt、轮次策略、信息方向、节点级模型服务/凭据、模型名、思考等级和备用模型配置。
- [x] credential profile 只引用 `site_id`，工作流版本、导出、运行日志和节点测试不得包含明文 key。
- [x] 运行详情展示 plan、ledger、checkpoint、重试、循环、评分和记忆。

## 验收

- [x] 原 v1 Council、Workflow version、AI debate 测试保持通过。
- [x] v2 成功、失败、循环、预算、恢复、人工等待和学习边界测试通过。
- [x] 全量 Python 测试和 `compileall` 通过。
- [x] 前端 production build 通过。
- [x] API、1536/1280/390 浏览器和常驻 Web/Worker smoke 通过。
- [x] 运行时模拟提交开关保持关闭，真实盘能力继续硬禁止。

## 状态

已完成，2026-07-12。详细证据、兼容性和回滚点见 `docs/reports/25-ai-workflow-v2-acceptance.md`。
