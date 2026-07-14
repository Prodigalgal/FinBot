# Phase 8 Autonomous Research & Advisory Loop

## 1. 目标

P8 的目标是让 FinBot 从“可手动触发的研究/建议工具”升级为“可定时运行的自主研究闭环”：

- 自动定时循环收集信源。
- 执行传统清洗、证据标准化、去重和事件生成。
- 执行 AI compression，并保持可审计、不可作为事实源的边界。
- 构建 P3 research cards、P4 briefing、P4.1 review council。
- 接入交易所公共行情，生成 advisory-only 产品候选。
- 通过 AI 多 Agent 辩论、Chair 合成和本地风险门禁输出最终建议。
- 输出 `recommended_products`，供人工确认，不触发真实交易。
- Web 页面展示调度状态、运行步骤、最近建议和错误状态。

## 2. 范围

后端：

```text
finbot/autonomous/
finbot/storage/sqlite_store.py
finbot/config/runtime_config.py
finbot/web/service.py
finbot/cli/serve_web.py
```

前端：

```text
web-ui/src/App.tsx
web-ui/src/api.ts
web-ui/src/types.ts
web-ui/src/utils.ts
```

配置和文档：

```text
config/runtime_config.example.json
docs/15-phase8-autonomous-research-loop.md
tasks/current.md
```

## 3. 非目标

- 不接下单、撤单、转账、改仓等真实交易执行 API。
- 不让 AI 自主调用交易接口。
- 不引入 RAG、向量库、外部队列或分布式 worker。
- 不开放自由 Agent swarm 任意工具调用；P8 AI Agent 只能消费结构化上下文并返回 JSON。
- 不绕过 Firecrawl 代理池约束。

## 4. 架构

```text
AutonomousLoopScheduler
  -> AutonomousLoopConfig.from_runtime_config()
  -> AutonomousResearchLoopRunner
    -> ResearchPipelineRunner
      -> ingestion / normalization / macro / market confirmation
      -> AI compression
      -> research cards / validation / promotion / follow-ups
      -> P4 brief / P4.1 council
    -> OperatorWorkbenchBuilder
      -> public exchange market data
      -> AdvisoryEngine
    -> ProductCandidateBuilder
      -> market candidates / research-only watch candidates
    -> AIDebateCouncilRunner
      -> Bull Researcher / Bear Researcher / Market Structure / Risk Controller
      -> Chair Arbiter trade synthesis
      -> local policy gate
    -> ProductRecommendationSelector
      -> recommended_products
    -> SQLiteStore autonomous_loop_* tables
    -> data/reports/autonomous-loop-latest.json
```

设计取舍：

- 使用内置轻量线程调度器，不引入 Celery / APScheduler / 外部队列，降低部署复杂度。
- 自动循环状态独立落 `autonomous_loop_runs` / `autonomous_loop_steps` / `autonomous_loop_artifacts`，不污染 research pipeline 运行表。
- P8 runner 只做应用层编排，不复制 P1-P7 业务规则。
- AI debate 与 AI trade synthesis 使用 `config/ai_sites.json` 中的 task binding 和 prompt，可热更站点、模型和提示词。
- AI 输出必须经过本地 policy gate：低置信度、缺研究确认、缺目标/失效价位时，方向建议会降级为 `WATCH`。
- 配置全部通过 `RuntimeConfigStore` 热读取；保存后下一轮调度或手动触发生效，正在运行中的循环不被中途改写。
- 交易所行情默认只启用 `gate`，通过 `exchange.enabled_public_providers`、`operator.providers`、`autonomous.providers` 热更后可增加 `binance` 或 `bybit:linear`。
- `recommended_products` 优先从 AI trade decisions 中筛选；AI 不可用时回退到 operator workbench 的确定性 advisory output。
- 所有产品建议保留 `execution_allowed=false` 和人工确认约束。

## 4.1 P8.1-P8.7 执行阶段

- P8.1 Autonomous Loop Contract：调度器热读取配置，runner 持久化每一步输入、输出、耗时、错误和 artifact。
- P8.2 AI Debate Council：多个 AI 角色分别读取相同候选上下文，输出结构化观点、反证和风险。
- P8.3 Product Candidate Mapping：从 operator workbench 行情建议和 P4.1 impact assets 生成统一候选。
- P8.4 AI Decision Synthesis：Chair Arbiter 综合候选、研究证据、确定性行情指标和角色消息。
- P8.5 Risk / Policy Gate：本地门禁强制 advisory-only、人工确认、置信度阈值和研究确认要求。
- P8.6 Operator Workbench UI：Web 自动循环页展示步骤、AI 辩论、AI 决策、产品建议和门禁原因。
- P8.7 Observability：新增 SQLite 表、状态 API、报告、单元测试和构建验证。

## 5. 数据表

新增表：

- `autonomous_loop_runs`
  - 记录一次 P8 循环的状态、触发来源、配置快照、摘要、开始/结束时间和错误。
- `autonomous_loop_steps`
  - 记录 `research_pipeline`、`operator_workbench`、`product_selection`、`publish_status` 的输入、输出、耗时和错误。
- `autonomous_loop_artifacts`
  - 保存每一步压缩后的产物引用，避免 UI 查询大 JSON。
- `ai_debate_councils`
  - 保存一次 AI debate council 的状态、模型、轮数、摘要和压缩 payload。
- `ai_debate_messages`
  - 保存每个 AI 角色每轮输出，包含 provider、model、stance、content 和错误。
- `ai_trade_decisions`
  - 保存 Chair 合成后经过本地 policy gate 的建议，包含 action、confidence、target、invalidation、position sizing、rationale、risk warnings 和 policy。

## 6. API

新增接口：

```text
GET  /api/v1/autonomous/status
POST /api/v1/autonomous/run-now
```

`GET /api/v1/autonomous/status` 返回：

- scheduler 状态。
- 当前热配置快照。
- 最近 10 次 autonomous loop。
- 最近建议产品。
- 最近 AI debate council。
- 最近 AI trade decisions。
- advisory-only policy。

`POST /api/v1/autonomous/run-now`：

- 触发一次后台 P8 循环。
- 如果已有循环正在运行，返回 `409`。
- 不执行交易。

## 7. 前端

新增一级菜单：

- 自动循环

页面展示：

- 调度状态、启用状态、运行中状态、循环间隔、下次运行。
- 最近 loop run 和步骤时间线。
- AI 多 Agent 辩论：角色、轮次、状态、provider、主要观点。
- AI 决策与风控：动作、置信度、目标价、失效价、风控门禁原因。
- 最新产品建议：标的、provider、动作、置信度、入场参考、目标价和周期。

系统配置：

- `自动循环` 配置组归入 `系统配置 -> 研究与交易`。
- 可热更开关、间隔、采集/AI/补证据、operator workbench、symbols/providers/intervals、建议筛选阈值。

## 8. 运行边界

- `autonomous.enabled=false` 是默认值，服务启动后不会自动发起外部请求。
- 用户启用后，调度器按 `autonomous.interval_minutes` 循环运行。
- 手动运行走同一套 runner 和状态表。
- Firecrawl 仍必须走代理池。
- exchange 数据只使用公共行情或只读预检，不开放订单端点。
- AI compression 可使用 DeepSeek / MiMo OpenAI-compatible 配置；AI 输出仍是上下文压缩，不是事实源。
- AI debate / trade synthesis 可使用 DeepSeek / MiMo 或其他 OpenAI-compatible 站点；AI 输出不是事实源，必须被本地 policy gate 约束。
- 系统可以输出 `BUY` / `SELL` / `HOLD` / `WATCH` 作为人工参考建议，但不会调用真实交易执行 API。

## 9. 验证

建议验证：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
cd ..
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
```

HTTP smoke：

```text
GET  /health
GET  /api/v1/status
GET  /api/v1/autonomous/status
POST /api/v1/autonomous/run-now
GET  /api/v1/reports/latest/autonomous-loop
```

浏览器 smoke：

```text
http://127.0.0.1:8780/ -> 自动循环页面可打开，桌面/移动端无横向溢出。
```

## 10. 后续扩展

- 增加 Web 鉴权、配置审计和运行权限分层。
- 增加更细的 prompt version / model version 审计。
- P9 再评估是否引入 RAG / 向量索引，用于长期专题研究，不进入高频新闻主路径。
