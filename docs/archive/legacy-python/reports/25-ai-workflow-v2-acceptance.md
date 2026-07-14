# AI Workflow v2 P0-P2 验收与变更记录

## 结论

AI Workflow v2 P0-P2 已完成。系统保留 v1 Council 兼容路径，并新增自由编排、真实多轮辩论、Director、持久化恢复、五套模板、学习闭环和统一工作流操作台。所有交易能力仍为 advisory/paper-only，真实盘执行继续禁止。

## 变更分类

### Workflow 契约与执行

- `finbot/council/models.py`：v1/v2 双版本、9 种节点、节点上下文/重试策略、条件边、`all/any`、安全循环、round policy。
- `finbot/council/conditions.py`：结构化字段路径和白名单运算符；不执行代码、表达式或任意 HTTP。
- `finbot/council/workflow_engine.py`：重试、预算、停滞检测、有限循环、human wait/resume 和幂等 checkpoint。
- `finbot/council/orchestrator.py`：phase participation、上下文裁剪、真正的 moderated 调度和多轮停止条件。
- `finbot/council/runtime.py`：运行/续跑、版本快照精确执行、ledger/checkpoint 持久化和敏感字段脱敏。

### Director、模板与学习

- `finbot/council/director.py`：按 trigger/query/产品/证据/深度选择模板，预算不可被 LLM 扩大。
- `finbot/council/builtin_templates.py`：快速扫描、标准研究、深度投委会、事件冲击、持仓复核五套 v2 模板。
- `finbot/council/learning.py`：角色效果评分、结构化 reflection、来源引用、过期和选择性记忆。
- `finbot/autonomous/config.py`、`runner.py`、`ai_debate.py`：常驻 Worker 接入 Director 选模、v2 深度和 learning 开关；Director plan 随 Council 审计落库。
- `finbot/config/runtime_config.py`：新增四个带默认值的 v2 运行配置；现有配置文件无需人工补字段。

### API、数据与 UI

- `finbot/storage/sqlite_store.py`：additive 新增 workflow run/ledger/checkpoint、role score 和 memory 表，不改旧表。
- `finbot/web/service.py`：新增 schema、Director plan、run/resume、learning/retrieve API；AI 配置只返回 key 是否已配置。
- `web-ui/src/CouncilWorkflowPanel.tsx`：工作流成为主操作台；模板/版本、策略、节点库、画布、检查器和运行面板同屏。
- `web-ui/src/WorkflowInspectorPanel.tsx`：节点级 provider/key profile、协议、模型、思考等级、Prompt、阶段、上下文、重试，以及边条件、汇合、传递和循环配置。
- `web-ui/src/WorkflowRunConsole.tsx`：Director 计划、Dry-run、人工续跑、进度、检查点、反思记忆和角色评分。
- `web-ui/src/councilWorkflowUtils.ts`、`types.ts`、`api.ts`：v1 升级、v2 校验、可视化映射和前后端类型契约。
- `output/imagegen/finbot-workflow-v2-concept.png`：用 sub2api imagegen 生成的界面概念参考；最终代码沿用现有 MUI 设计系统实现。

## 行为与兼容性

- 旧 v1 模板、已发布快照和原 API 字段继续有效；v2 字段均为 additive。
- 用户可复制内置模板为自定义工作流；legacy v1 模板只有显式点击才升级，不做静默改写。
- 草稿 Dry-run 按 `workflow_version_id` 读取指定版本快照，不再误用当前发布配置。
- 节点只持久化 `site_id`、model、protocol、reasoning；原始 key 仍由 AI site/credential profile 管理。
- UI 和运行日志不展示 hidden chain-of-thought，只展示结构化观点、证据、质询、状态和审计信息。
- 通用 v2 run API 的非 dry-run 模式继续要求受控外部节点执行器；常驻真实 AI 多轮由现有 Council runner 执行，避免在 Web 请求线程内开放任意同步模型调用。

## 验证证据

- Python：`137` 个 `unittest` 全部通过；`python -m compileall -q finbot` 通过。
- Frontend：`npm run build` 通过；工作流 chunk 独立懒加载。仅保留 MUI vendor chunk 大小提示。
- 五套模板：保存草稿、成本预估、节点测试和 Dry-run 全部通过；深度模板完成 human wait/resume。
- 真实 AI：标准模板使用 `sub2api / Responses / gpt-5.6-luna`，1 轮、1 候选，6 个角色 + 1 个 Chair 全部完成；30,608 Token，估算 `$0.26242`，临时数据库，交易执行为 false。
- Live API：schema 返回 9 种节点、6 套模板、latest version 2；快速 smoke 6 个 checkpoint 完成。
- 浏览器：1536px 三栏画布 699px；1280px 宽画布 773px、检查器下置；390px 无横向溢出、画布 349px；最终控制台 0 error/0 warning。
- 常驻运行：`FinBot-Web` PID `4536`、`FinBot-Worker` PID `39604`；Worker active/idle、无 current request；`paper_execution.submit_orders=false`。

## 部署状态

- 本地 Web/Worker 已加载本次代码和前端构建，访问地址：`http://127.0.0.1:8780/`。
- 本次未执行 K8S 发布。后续部署继续遵守现有单 Pod 双容器、共享 PVC、`replicas=1`、`Recreate` 约束，避免 SQLite 多写者。

## 遗留风险

- 通用 v2 live run 尚未在 Web API 内直接绑定同步 LLM executor；这是安全和请求时长边界，不影响 Council runner 的真实多轮执行或工作流 Dry-run。后续应通过 Worker queue 执行 live run，而不是在 FastAPI 请求线程中直调模型。
- MUI vendor chunk 约 514 KB；工作流本身已 lazy-load，后续可继续拆分全局 MUI imports。
- TestClient 输出 `starlette/httpx` deprecation warning；当前 137 个测试均通过，升级依赖时需复验。

## 回滚点

- 配置回滚：设置 `autonomous.workflow_engine_version=1`，或关闭 `workflow_director_enabled` / `workflow_learning_enabled`。
- 模板回滚：从版本下拉选择旧版本并发布回滚副本；历史版本不原地修改。
- UI 回滚：恢复 `CouncilWorkflowPanel.tsx` 及新增 inspector/console 文件，后端 v2 additive schema 可保留。
- 安全回滚：保持 `paper_execution.submit_orders=false`；新增表无需删除，v1 读路径会忽略。
