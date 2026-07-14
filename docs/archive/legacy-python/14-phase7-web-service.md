# Phase 7 Web 服务化设计

## 1. 目标

将 FinBot 从 CLI / 离线流水线扩展为长期运行的标准 Web 服务：

- 后端使用 FastAPI / Uvicorn。
- 前端使用 React + TypeScript + MUI。
- Web 层统一编排 P1-P6 既有能力，不复制业务规则。
- 中文单语界面和中文服务说明，不做多语言 / i18n 分支。
- 系统配置通过 Web 界面热更，下一次请求和新后台任务读取最新配置。
- 保持 `advisory_only`：允许展示交易建议，不允许下单、撤单、转账或改仓。

## 2. 架构

```text
React + TS + MUI
  -> FastAPI routes
    -> FinBotWebApp application service
      -> ResearchPipelineRunner
      -> OperatorWorkbenchBuilder
      -> ProxyRuntime / ProxyRouter
    -> RuntimeConfigStore
    -> AutonomousLoopScheduler / AutonomousResearchLoopRunner
    -> SQLiteStore / reports
```

边界：

- API 层只负责请求校验、后台任务提交、状态查询和静态前端托管。
- 应用服务层复用现有 CLI 背后的 domain/service。
- 存储仍使用当前 SQLite 和 `data/reports`。
- 字段名、状态枚举和 provider id 作为 API 契约保留英文；用户可见 UI 文案只使用中文。
- 运行时配置落 `config/runtime_config.json`，代理路由策略落 `config/proxy_policy.json`；两个真实文件都不提交。

## 3. 后端入口

核心文件：

```text
finbot/web/service.py
finbot/cli/serve_web.py
```

启动：

```powershell
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
```

主要接口：

```text
GET  /health
GET  /docs
GET  /api/v1/status
GET  /api/v1/autonomous/status
POST /api/v1/autonomous/run-now
GET  /api/v1/config
PUT  /api/v1/config
GET  /api/v1/ai/config
PUT  /api/v1/ai/config
POST /api/v1/ai/config/models/refresh
GET  /api/v1/jobs
GET  /api/v1/jobs/{job_id}
GET  /api/v1/reports/latest/{kind}
GET  /api/v1/operator/workbench/latest
GET  /api/v1/proxy/diagnostics
POST /api/v1/jobs/research-pipeline
POST /api/v1/jobs/operator-workbench
POST /api/v1/jobs/proxy-diagnostics
```

后台任务：

- `research-pipeline` 默认 `dry_run=true`。
- `operator-workbench` 可启动 sing-box bridge，并保持 `execution_allowed=false`。
- `proxy-diagnostics` 可检查 Firecrawl / exchange route。
- `autonomous-loop` 由内置调度器或手动触发，串联研究流水线、交易建议工作台和产品筛选。

配置热更：

- Web 配置页只允许修改注册表内的配置项，后端做类型校验和范围校验。
- 密钥、代理池、VLESS 订阅地址等敏感配置可以写入，但 API 不回显明文。
- 保存后写入 `config/runtime_config.json` 或 `config/proxy_policy.json`，下一次状态查询、代理诊断、报告读取和新后台任务会重新读取。
- 已经运行中的后台任务不会被中途改写；服务监听地址、依赖安装、已启动的外部子进程仍属于生命周期配置，需要重启或重新提交任务。
- Firecrawl 路由仍禁止 direct fallback；配置界面只允许调整代理池和 VLESS 桥接，不开放 Firecrawl 直连。

AI 配置：

- AI 配置独立落 `config/ai_sites.json`，真实文件已加入 `.gitignore`；示例文件是 `config/ai_sites.example.json`。
- 配置层分为站点、环节和提示词：
  - 站点：`site_id`、显示名、base URL、API Key、Chat 模型、Responses 模型、默认模型、超时。
  - 环节：例如 `ai_compression`，可选择站点、协议、模型和备用站点。
  - 提示词：每个 AI 环节有 system prompt 和 user prompt template。
- `user_prompt_template` 当前支持 `{payload_json}`、`{target_type}`、`{target_id}`。
- 模型列表可按站点和协议从 OpenAI-compatible `/models` 刷新：
  - Chat 刷新写入 `chat_models`。
  - Responses 刷新写入 `responses_models`。
  - 刷新需要站点地址和密钥；密钥可来自 `config/ai_sites.json` 或服务器管理 key 文件。
- 若 `config/ai_sites.json` 不存在，后端仍兼容旧的 DeepSeek / MiMo 环境变量和 key 文件。

## 4. 前端入口

核心目录：

```text
web-ui/
```

技术栈：

- Vite
- React 18
- TypeScript
- MUI

运行：

```powershell
cd web-ui
npm install
npm run dev
```

构建：

```powershell
cd web-ui
npm run build
```

当前页面：

- 总览：SQLite 计数、最新流水线、最新交易建议、后台任务。
- 自动循环：展示调度状态、最近 P8 loop run、步骤时间线和最新产品建议。
- 交易建议：提交交易建议后台任务。
- 研究流水线：提交研究流水线后台任务。
- 代理诊断：同步检查或提交后台代理诊断。
- 系统配置：一级菜单下显示二级子菜单，分域热更运行时配置、代理策略、AI 站点、AI 环节、AI 提示词和任务默认值。
- 报告查看：读取最新报告。

## 5. 中文支持策略

- 系统界面、按钮、导航、面板标题和服务说明只使用中文。
- API 字段名、枚举值、provider id、symbol、route id 保持英文，作为工程契约。
- 原始 JSON 报告按 API 契约展示，不做字段翻译，以避免破坏可审计性。

## 6. 验证

已验证：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui; npm run build
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
```

HTTP smoke：

```text
GET /health -> {"status":"ok","service":"finbot-web"}
GET /api/v1/status -> status=ok, execution_allowed=false
GET /api/v1/config -> 返回配置 schema、当前值状态、运行时配置文件路径和代理策略文件路径
GET /api/v1/ai/config -> 返回 AI 站点、环节绑定和提示词配置，不回显密钥
POST /api/v1/ai/config/models/refresh -> 可按站点和协议刷新模型列表
GET /docs -> FastAPI 文档可打开，标题为 FinBot 网页服务
POST /api/v1/jobs/proxy-diagnostics -> 202 accepted，job 最终 succeeded
```

浏览器 smoke：

```text
http://127.0.0.1:8780/ -> React 中文工作台可加载
desktop screenshot -> data/reports/web-ui-desktop.png
mobile screenshot -> data/reports/web-ui-mobile.png
```

## 7. 后续建议

- 增加 API key / 本地登录保护，避免服务暴露后被误触发任务。
- 增加 job 持久化表，替代当前内存 job manager。
- 前端增加 job 详情页、报告结构化视图和配置变更审计历史。
- 将 Web API 的请求/响应模型补到独立 contract 文档。
