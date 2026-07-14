# P0-P1 产品闭环与质量收口 Goal

## 目标

- 修复当前审计确认的 P0 数据可信度、结果语义和常驻恢复问题。
- 贯通“产品/Watchlist -> 研究 -> 多 Agent 辩论 -> 人工复核 -> 模拟执行 -> 结果评估”的 P1 主流程。
- 完成既有 P3.2、P3.3、P3.4 里程碑，不引入真实盘交易能力。

## 范围与顺序

### P0 数据与运行可信度

- [x] 无有效价格或主周期 K 线的场所合约必须标记为 `insufficient_data`，不得进入建议、候选或模拟执行。
- [x] Hybrid Universe 对研究资产只选择每个交易所/市场的代表合约；未显式指定时排除到期交割合约。
- [x] 分离“流程运行状态”和“决策就绪度”，证据待补时不得显示为可执行结论。
- [x] 统一即时、自动循环、总览和报告页的产品建议契约与展示组件。
- [x] AI 主席输出默认使用简体中文；分歧、缺失证据和失效条件为必填结构字段。
- [x] Worker lease 失效后收敛遗留 `running` loop/pipeline，并修复常驻任务的启动、重启与状态证据。

### P1 产品工作流

- [x] 产品中心支持直接发起即时研究，并携带产品、首选场所合约和 Watchlist 上下文。
- [x] 新增研究/建议历史、版本对比、失败续跑、运行重放和人工复核收件箱。
- [x] Council Workflow 支持草稿、发布、版本、回滚、节点单测和运行前成本预估。
- [x] 完成 Shadow Portfolio、回测、置信度校准、受控反思和通知策略。
- [x] 在 Gate TestNet 与 Bybit Demo 执行一次受控并发模拟验收：每家最多 1 单、单笔最多 100 USDT、真实盘硬禁止。
- [x] 修复页面切换滚动、1280px 产品中心溢出、工作流检查器上下文丢失，并补齐桌面/移动回归。

## 当前状态（2026-07-12）

- P0-P1 功能、API、持久化、UI 与首次双交易所模拟写入全部完成。
- 研究闭环 `68c6584a7ce3ffd4bcd79bf80c7e7f1d` 的研究、目录、Universe、operator、三轮 Council、交易合成、产品筛选、评估、风险和治理步骤均通过，生成 6 条 `market-confirmed` 方向性建议；DOGEUSDT SELL 置信度 0.70，经人工复核批准。
- 模拟执行 `c3957c42717a374de81ecf53d1221c55` 为 `passed`：Gate TestNet `DOGE_USDT` 名义 99.8963 USDT、状态 `filled`；Bybit Demo `DOGEUSDT` 名义 99.98472 USDT、状态 `submitted`，后续私有持仓查询确认两家均为真实模拟空仓持仓。数据库仅有该决策的 2 条 adapter 唯一记录。
- `paper_execution.submit_orders` 已在 `finally` 恢复为 `false`；真实盘 host 继续硬禁止。模拟仓位保留开放，未额外发送平仓单。
- Shadow Portfolio 刷新为 `ready`，1 个开放影子仓位、mark coverage 100%；通知收件箱已生成方向性建议提醒。
- 正式环境以 K8S 为目标；新增独立 `finbot-egress-proxy`，通过 `infra.mnnu/location=sg` 固定新加坡出口。目标集群 Kustomize client dry-run 全部通过，但尚未正式部署，当前 SG 节点也尚未补该标签。
- 全量验证：112/112 Python 测试、`compileall`、Vite production build、HTTP smoke、既有 1536/1280/390 浏览器 smoke 和 console 检查通过。

## 非目标与安全边界

- 不开放真实交易、主网私有 API、真实订单端点或自动资金划转。
- 不对完整产品库逐一调用 LLM；产品库、Watchlist、动态 Universe 和深度研究保持分层。
- 不在本 Goal 内实现云端多租户；默认仍为本地单用户。
- 模拟订单属于外部写入，仅在只读探针、风险门禁、幂等键和用户已授权范围同时满足时执行。

## 主要影响模块

- 数据与运行：`finbot/instruments/`、`finbot/operator/`、`finbot/autonomous/`、`finbot/storage/`、`finbot/web/`。
- AI 与评估：`finbot/autonomous/ai_debate.py`、`finbot/evaluation/`、`finbot/exchange/`。
- Web UI：`apps/web/src/App.tsx`及同目录的产品、研究、运维与工作流页面。
- 契约与专案：`docs/requirements/`、`docs/decisions/`、`tasks/in-progress/`。

## 验收标准

- 空价格、0 主周期 K 线、过期交割合约不会伪装成正常建议。
- 页面同时显示运行健康与决策就绪度；相同产品在不同交易所/市场可明确区分。
- 产品中心可一键进入预填研究，最终结果可进入人工复核和受控模拟流程。
- 历史、重放、续跑、工作流发布/回滚、节点测试和成本预估均有 API、持久化与 UI 入口。
- Shadow Portfolio 和评估报告能展示样本量、收益、回撤与校准状态，不在样本不足时输出伪指标。
- Gate TestNet / Bybit Demo 模拟验收具备请求、响应、幂等和风险审计记录；真实盘能力继续硬禁止。
- Python 全量测试、前端构建、HTTP smoke、1536/1280/390 浏览器 smoke 和常驻恢复验证通过。

## 验证命令

```powershell
python -m compileall finbot
python -m unittest discover -s tests -v
Set-Location apps/web
npm run build
```

- API：状态、产品、Watchlist、即时研究、历史/复核、工作流版本、模拟执行、评估与报告。
- 浏览器：产品到研究主流程、结果一致性、人工复核、工作流编辑/发布、桌面与移动布局。
- 运行态：计划任务启动、Worker heartbeat、过期 lease 恢复、遗留运行收敛和下一轮定时执行。
