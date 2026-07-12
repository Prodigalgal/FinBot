# 当前任务

## S2：Firecrawl IPv4 订阅池与代理抽象加固

- 目标：Firecrawl keyless 使用独立 IPv4 VLESS 订阅池；Bybit 保持现有 IPv4 交易所代理；代理候选具备健康反馈、冷却和脱敏观测。
- 范围：VLESS Subscription Source、sing-box Protocol Bridge、health-aware ProxyPool、ProxyRouter、Firecrawl/交易所 consumer、K8S Secret 与生产镜像。
- 非目标：不把订阅 token 或节点 UUID 写入仓库，不开放 direct fallback，不修改 Gate/Bybit 凭据和模拟下单开关。
- 影响文件：`finbot/config/`、`finbot/network/`、`finbot/ingestion/`、`finbot/market/`、`deploy/k8s/`、`Dockerfile`、`tests/`。
- 验收标准：订阅可解析并去重；Linux 镜像包含校验过的 sing-box；坏节点进入指数冷却；Firecrawl keyless 真实请求通过订阅节点成功；Bybit 仍走独立 IPv4 出口。
- 测试方式：代理/订阅/bridge 单测、全量 Python 测试、前端构建、镜像与 Kustomize dry-run、CI、ArgoCD、Pod 内出口与 API smoke。

## S2：全局标题与操作动线优化

- 目标：按用户任务重组导航和页面标题，降低“自动研究、快速市场分析、手动采集处理、复核与报告”之间的理解成本，并在工作台提供高频入口。
- 范围：全局导航分组、页面标题与副标题、工作台常用入口、研究/配置/网络/报告页面的人类可读文案。
- 非目标：不修改 API、页面状态 ID、任务 kind、研究流程、交易逻辑、视觉主题或权限边界。
- 影响文件：`web-ui/src/App.tsx`、`web-ui/src/OperationsPanels.tsx`、`web-ui/src/ResearchGovernancePanel.tsx`、`web-ui/src/InstantResearchPanel.tsx`、`web-ui/src/humanReadable.ts`、`web-ui/src/utils.ts` 及关联页面的展示文案。
- 验收标准：桌面端按任务分组显示全部既有功能；移动端可理解并访问相同入口；工作台可直接进入产品、自主研究、复核和账户；无横向溢出和控制台错误。
- 测试方式：`npm run build`，桌面端 `1536x1024` 与移动端 `390x844` Playwright smoke，关键导航交互与页面标题断言。
- 状态：已完成（2026-07-12）。
- 验证结果：前端生产构建通过；Playwright 在 `1536x1024` 与 `390x844` 完成工作台、产品、即时研究、自动研究、系统设置和辩论工作流跳转，控制台错误为 0，页面宽度分别为 `1536/1536` 与 `390/390`，无横向溢出。
- 运行态：`FinBot-Web` 已重新加载最新构建，`GET /health` 返回 200；仅保留既有 MUI vendor chunk 大小提示。

## P3 产品研究工作台专案（进行中）

目标：把产品库、用户 Watchlist、Hybrid Universe、研究历史、Council 工作流和建议治理收敛为一个长期专案。当前推进 P3.1 产品中心与 Watchlist 端到端闭环，详细范围与状态见 `docs/requirements/22-p3-product-research-workspace.md`、`docs/decisions/005-p3-product-catalog-watchlist-universe.md` 和 `tasks/in-progress/P3-product-research-workspace.md`。

状态：P3.1-P3.4 与 P3.1B 已完成；Gate TestNet 与 Bybit Demo 首次受控并发模拟写入已通过，提交开关已恢复关闭，正式 K8S 部署另行推进。

### S2：交易所订阅优先节点标签

- 目标：允许按订阅节点标签稳定优先选择已实测可用的交易所代理，同时保留在线订阅更新。
- 范围：`Settings`、运行时配置 schema、VLESS 订阅内存排序、单元测试和本机敏感运行时值。
- 非目标：不复制节点 UUID 到仓库，不修改订阅服务，不放开直连，不开启模拟下单。
- 影响文件：`finbot/config/settings.py`、`finbot/config/runtime_config.py`、`finbot/network/proxy_runtime.py`、相关测试及本机 `config/runtime_config.json`。
- 验收标准：优先标签命中时稳定排首位，未命中时保持原顺序；标准 Gate/Bybit 只读探针均通过；真实盘与写请求继续关闭。
- 测试方式：代理排序/运行时配置单测、全量 Python 单测、凭据探针、HTTP 和浏览器状态 smoke。
- 状态：已完成；标准探针 Gate/Bybit 均通过，最近自动循环 13/13 通过并同步 722 个 Bybit 活跃产品。

### S2：Bybit Hysteria2 稳定出口接入

- 目标：在已筛选 VLESS 出口发生地区漂移后，接入用户提供并重新验证的 Hysteria2 节点，恢复 Bybit Mainnet 与 Demo 私有只读访问。
- 范围：敏感运行时配置、Hysteria2 URL 解析、sing-box bridge、多节点失败隔离、账户读取与代理回归测试。
- 非目标：不修改真实盘禁令，不开启模拟下单，不把节点密码、订阅内容或鉴权信息写入 API/日志/仓库。
- 影响文件：`finbot/config/`、`finbot/network/`、`.env.example`、相关测试及本机 `config/runtime_config.json`。
- 验收标准：节点按实时可用性排序；Bybit Mainnet/Demo 公共接口和 Demo 私有只读鉴权通过；bridge 关闭后删除含凭据临时配置；VLESS 保持备选。
- 测试方式：解析与 bridge 单测、运行时配置脱敏测试、全量 Python 回归、真实只读代理探针和账户 API smoke。
- 状态：已完成（2026-07-12）。
- 实现：新增 `exchange.hysteria2_urls` 敏感多行配置、Hysteria2 URL/端口跳跃解析、通用 sing-box bridge、多节点启动失败隔离和关闭清理；Hysteria2 优先，既有 VLESS 保持备选。
- 真实验证：4/4 节点均通过两轮 Bybit Mainnet/Demo 公共 API 和 Demo 私有持仓只读鉴权，写请求为 0；最终按 Osaka JP、JP、JP、SG 排序。
- 账户验收：主服务 `GET /api/v1/exchange-accounts?pnl_range=all` 返回 `ok`，Gate TestNet 与 Bybit Demo 均为 `ready`，Bybit 返回权益、累计已实现盈亏、当前未实现盈亏和 1 个持仓。
- 安全验收：配置 API 对节点 URL返回 `value=null`；`paper_execution.submit_orders=false`、Bybit direct fallback=false；清理旧版遗留的 775 个 bridge JSON，后续关闭时自动删除。
- 回归：`122/122` Python 测试通过，`compileall` 通过，前端生产构建通过；仅保留既有 MUI vendor 包体积提示。
- 运行态：`FinBot-Web`、`FinBot-Worker` 均为 `Running`，`8780/health` 返回 200，Worker active heartbeat 为 1。
- K8S 边界：正式集群仍使用独立 `finbot-egress-proxy` 固定出口；本地 Hysteria2 bridge 不进入应用容器。

## Phase 2 工程化维护与四步增强

目标：

- 先做工程化维护，收敛重复 CLI 初始化和报告输出。
- 推进到 Phase 2 第四步：
  1. 结构化宏观事实标准化。
  2. 市场确认增强。
  3. 补证据调度。
  4. Firecrawl / provider 预算和限流状态。
- 明确 Source Layer 到 AI Research Layer 之间是否需要 AI 辅助压缩，以及如何保证可审计。

范围：

- `finbot/cli/`
- `finbot/macro/`
- `finbot/market/`
- `finbot/research/`
- `finbot/scheduling/`
- `finbot/budget/`
- `finbot/storage/sqlite_store.py`
- `docs/07-phase2-detailed-design.md`

非目标：

- 不接交易权限 API。
- 不引入真实 LLM 调用。
- 不把 AI 压缩结果作为事实源。
- 不依赖缺失的 FRED/BEA/Alpha Vantage/OpenBB key。

验收标准：

- 新增能力有 CLI 可运行入口。
- Phase 2 package 输出 macro facts、market snapshots、follow-up jobs、budget state。
- 缺 key 不阻塞。
- `python -m compileall finbot` 通过。
- 关键 CLI smoke 通过。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.run_macro_facts
python -m finbot.cli.run_market_confirmation
python -m finbot.cli.plan_corroboration
python -m finbot.cli.update_budget_state
python -m finbot.cli.build_phase2_package --time-window phase2-step4
python -m finbot.cli.status
```

当前结果：

- `compileall` 通过。
- `macro_release_facts`: 7。
- `market_context_snapshots`: 9。
- `queued-corroboration` jobs: 12。
- `source_budget_state`: 33，全部 `ok`。
- Phase 2 package 已输出 `compression_plan`，当前建议 AI 辅助压缩。

## Phase 2 AI compression provider 接入

目标：

- 在 Source Layer 和 AI Research Layer 之间接入可审计 AI compression。
- 支持 DeepSeek / MiMo 两类 provider。
- provider 只实现 OpenAI-compatible Chat Completions / Responses 协议簇。
- 密钥从 `D:\WorkSpace\Project\服务器管理\private\ai-providers\keys.env` 或环境变量读取，不写入仓库。

范围：

- `finbot/ai/`
- `finbot/config/`
- `finbot/research/`
- `finbot/storage/sqlite_store.py`
- `finbot/cli/`
- `docs/07-phase2-detailed-design.md`
- `.env.example`

非目标：

- 不把 AI compression 输出作为事实源。
- 不支持非 OpenAI-compatible SDK。
- 不暴露、打印或持久化 API key。
- 不因某个 provider 配置不完整而阻塞其他 provider 或整体流程。

验收标准：

- 新增 CLI 可执行 dry-run，能列出候选和 provider 配置状态。
- DeepSeek key 存在时可以通过 Chat Completions 协议做最小压缩 smoke。
- DeepSeek 和 MiMo 默认值按官方 OpenAI-compatible 文档配置；若运行时覆盖导致缺项，则标记为 disabled/misconfigured，不崩溃。
- 压缩结果进入 `ai_compressions` 表并进入 Phase 2 package 汇总。
- `python -m compileall finbot` 通过。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.run_ai_compression --dry-run
python -m finbot.cli.run_ai_compression --provider deepseek --protocol chat --limit-documents 1 --limit-events 0
python -m finbot.cli.build_phase2_package --time-window phase2-ai-compression
python -m finbot.cli.status
```

## Phase 3 AI Research Layer v1

目标：

- 将 Phase 2 处理好的事件、证据、宏观事实、行情上下文和 AI compression 组装成 `ResearchCard`。
- P3 暂不引入重型 RAG / 向量库，先使用 SQLite metadata retriever。
- 默认强时效优先，通过 Freshness Gate 过滤或降权过时新闻。
- 借鉴研究委员会式流程，但第一版先做 deterministic analyst modules，不做自由 Agent swarm。

范围：

- `finbot/research/`
- `finbot/cli/`
- `finbot/storage/sqlite_store.py`
- `docs/08-phase3-research-layer-design.md`
- `tasks/current.md`

非目标：

- 不引入 Qdrant / Chroma / pgvector / LanceDB。
- 不输出交易信号、仓位、买卖建议或价格预测。
- 不让过时新闻进入主证据链，除非是官方文件、宏观事实或明确背景材料。
- 不把 AI compression 结果当事实源。

验收标准：

- 新增 `research_cards` 表。
- 新增 `build_research_cards` CLI。
- 每张卡片包含 freshness policy、fresh evidence、stale context、discarded stale refs、evidence assessment、macro context、market context、AI compression refs、counter arguments、follow-up jobs 和 policy flags。
- 默认只处理 `research-ready` / `needs-corroboration`，可选包含 `watch-only`。
- `python -m compileall finbot` 通过。
- CLI smoke 能生成 report 并写入 SQLite。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.build_research_cards --time-window phase3-v1 --limit-events 10
python -m finbot.cli.status
```

## Phase 3 Research Card Validation

目标：

- 给 P3 `ResearchCard` 增加验收门，避免“生成即可信”。
- 验证引用链、时效门禁、AI compression 边界、policy gate 和必备字段。
- 生成可落库、可报告、可重复运行的 validation 结果。

范围：

- `finbot/research/card_validator.py`
- `finbot/cli/validate_research_cards.py`
- `finbot/storage/sqlite_store.py`
- `docs/08-phase3-research-layer-design.md`
- `tasks/current.md`

非目标：

- 不对卡片内容做主观投资判断。
- 不新增 LLM 审查。
- 不把 `needs-corroboration` 的缺证据状态当作失败，只作为 warning / follow-up。

验收标准：

- 新增 `research_card_validations` 表。
- 新增 `validate_research_cards` CLI。
- 能检查 card payload 必填字段、policy flags、forbidden trading terms、document/evidence/compression 引用存在性、freshness 自洽性。
- 验证报告写入 `data/reports/research-card-validations-latest.json`。
- `python -m compileall finbot` 通过。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.build_research_cards --time-window phase3-v1 --limit-events 10
python -m finbot.cli.validate_research_cards
python -m finbot.cli.status
```

## Phase 3 Research Card Promotion

目标：

- 对已验证的 `ResearchCard` 做研究优先级评分和流转决策。
- 将卡片分为 `active-watch`、`needs-followup`、`manual-review`、`archive-background`。
- 输出后续补证据任务和观察队列候选，但不生成任何交易动作。

范围：

- `finbot/research/card_promotion.py`
- `finbot/cli/promote_research_cards.py`
- `finbot/storage/sqlite_store.py`
- `docs/08-phase3-research-layer-design.md`
- `tasks/current.md`

非目标：

- 不做买卖、仓位、止损、目标价、交易信号。
- 不绕过 `ResearchCardValidator`。
- 不把 `needs-corroboration` 自动升级成 `research-ready`。

验收标准：

- 新增 `research_card_decisions` 表。
- 新增 `promote_research_cards` CLI。
- 只处理验证状态为 `passed` 或 `warning` 的卡片。
- 每条决策包含 score、decision、reasons、follow_up_jobs、watchlist_tags、policy flags。
- 报告写入 `data/reports/research-card-decisions-latest.json`。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.build_research_cards --time-window phase3-v1 --limit-events 10
python -m finbot.cli.validate_research_cards --clear-existing
python -m finbot.cli.promote_research_cards --clear-existing
python -m finbot.cli.status
```

## Phase 3 Follow-up Dispatch

目标：

- 将 `research_card_decisions` 中的 `follow_up_jobs` 转换成可执行 `fetch_jobs`。
- 让 P3 的 `needs-followup` 决策回流到 Phase 1/2 采集与补证据链路。
- 生成 dispatch 记录，保证后续可以追踪每个补证据 job 来源于哪张研究卡片。

范围：

- `finbot/research/followup_dispatch.py`
- `finbot/cli/dispatch_research_followups.py`
- `finbot/storage/sqlite_store.py`
- `docs/08-phase3-research-layer-design.md`
- `tasks/current.md`

非目标：

- 不直接执行网络采集，只入队 `fetch_jobs`。
- 不新增新的 provider。
- 不绕过 source catalog 既有 source id / job_type。
- 不生成交易动作。

验收标准：

- 新增 `research_followup_dispatches` 表。
- 新增 `dispatch_research_followups` CLI。
- `needs-followup` / `manual-review` 的 follow-up 可转换为 `queued-research-followup` fetch job。
- 相同 card/detail 生成稳定 job id，重复运行不膨胀 `fetch_jobs`。
- 报告写入 `data/reports/research-followup-dispatch-latest.json`。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.build_research_cards --time-window phase3-v1 --limit-events 10
python -m finbot.cli.validate_research_cards --clear-existing
python -m finbot.cli.promote_research_cards --clear-existing
python -m finbot.cli.dispatch_research_followups --clear-existing
python -m finbot.cli.status
```

## Phase 3 Follow-up Runner

目标：

- 执行 `fetch_jobs.status=queued-research-followup` 的补证据任务。
- 复用现有 `Dispatcher` / adapter / evidence store，不复制采集逻辑。
- 支持 dry-run、限量执行和 search 后 scrape follow-up 执行。
- 让 P3 从“生成补证据队列”进入“可真实跑补证据”的闭环。

范围：

- `finbot/research/followup_runner.py`
- `finbot/cli/run_research_followups.py`
- `finbot/storage/sqlite_store.py`
- `finbot/ingestion/adapters/firecrawl.py`
- `docs/08-phase3-research-layer-design.md`
- `tasks/current.md`

非目标：

- 不执行普通 scheduler 全量采集。
- 不新增 provider。
- 不绕过 source catalog。
- 不改变交易边界。

验收标准：

- 新增 `run_research_followups` CLI。
- dry-run 能列出 queued research follow-up jobs。
- live smoke 能执行至少 1 个 queued research follow-up job。
- 执行结果写入 `raw_evidence`、`fetch_runs`、`source_health`，并更新 `fetch_jobs.status`。
- `firecrawl_search_then_scrape` 按 job_type 正确触发搜索后详情页候选。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.run_research_followups --dry-run --max-jobs 3
python -m finbot.cli.run_research_followups --max-jobs 1 --max-discovered-jobs 1 --timeout-seconds 25
python -m finbot.cli.status
```

## Firecrawl Proxy Pool Guard

目标：

- 将 Firecrawl 代理池从可选配置提升为执行层硬约束。
- 禁止 Firecrawl search/scrape 使用 direct fallback。
- 当未配置 `FIRECRAWL_PROXY` / `FIRECRAWL_PROXY_POOL` / `FIRECRAWL_PROXY_FILE` 时，任务必须失败并明确提示代理池缺失。

范围：

- `finbot/network/proxy_pool.py`
- `finbot/ingestion/adapters/firecrawl.py`
- `finbot/config/settings.py`
- `.env.example`
- `config/firecrawl_proxies.example.txt`
- `docs/05-ingestion-collector-detailed-design.md`

验收标准：

- `ProxyPool(include_direct=False)` 在没有代理时不再返回 direct 候选。
- Firecrawl adapter 始终以 `include_direct=False` 构造代理池。
- 未配置代理时 Firecrawl job 不发起网络请求，直接返回失败原因。
- `python -m compileall finbot` 通过。

## Phase 3.5 Research Workflow Hardening

目标：

- 加固 P3 follow-up 从“可执行”到“更稳健可运营”。
- 使用此前验证过的 Firecrawl SOCKS5 代理池，不允许 Firecrawl 裸连。
- 清洗 follow-up query，避免工作流标签污染搜索。
- 将官方确认、独立二次来源、行情上下文等 follow-up 分流到更合适的 provider/source。
- Runner 增加 provider budget/backoff，避免连续撞 429 或代理失败。
- 支持 follow-up 执行后触发 research card rebuild/validate/promote 的本地闭环。

范围：

- `config/firecrawl_proxies.txt`，私有忽略文件。
- `finbot/research/followup_dispatch.py`
- `finbot/research/followup_runner.py`
- `finbot/cli/run_research_followups.py`
- `finbot/storage/sqlite_store.py`
- `docs/08-phase3-research-layer-design.md`
- `tasks/current.md`

非目标：

- 不引入 RAG / 向量库。
- 不新增交易动作或交易信号。
- 不绕过 `ResearchCardValidator`。
- 不把代理地址、key 或敏感凭据写入可提交文件。

验收标准：

- `config/firecrawl_proxies.txt` 存在且不被 git 跟踪。
- Dispatch query 不再包含 `needs-followup`、`needs-corroboration`、`fresh` 等工作流标签。
- 官方确认 follow-up 优先路由到相关 T1 官方 source；独立二次来源才使用 `search_firecrawl_global`。
- Runner 在 source budget 为 exhausted/throttled 时跳过执行并记录 skipped。
- Runner 遇到 Firecrawl 429 / proxy pool 缺失 / provider block 时写入 short throttle，避免同批任务连续撞墙。
- Runner 可通过参数在执行后 rebuild/validate/promote research cards。
- `python -m compileall finbot` 通过。
- dry-run 和小批量 follow-up smoke 可运行。

## Phase 4 Research Operations Briefing

目标：

- 将 P3/P3.5 已验证、已提升、可跟进的研究卡片汇总成可运营的研究简报。
- 生成 research watch items，作为观察队列、人工复核队列、补证据队列和背景归档队列的统一视图。
- 汇总 source health、credential blockers、provider throttles、follow-up queue，给出下一步运营动作。
- 保持 research-only，不输出交易信号、买卖、仓位、目标价或价格预测。

范围：

- `finbot/research/briefing.py`
- `finbot/cli/build_phase4_brief.py`
- `finbot/storage/sqlite_store.py`
- `finbot/cli/status.py`
- `docs/09-phase4-research-ops-briefing.md`
- `tasks/current.md`

非目标：

- 不接交易 API。
- 不生成交易动作、交易方向、仓位、止损、止盈或目标价。
- 不引入外部 RAG / 向量库。
- 不替代 `ResearchCardValidator` 或 `ResearchCardPromoter`。

验收标准：

- 新增 `research_watch_items` 表。
- 新增 `research_briefs` 表。
- 新增 `build_phase4_brief` CLI。
- P4 brief 只消费已存在的 card/validation/decision/follow-up/source health 状态。
- Brief payload 包含 summary、watch items、follow-up queue、source blockers、operator actions 和 policy gate。
- policy gate 能阻止交易动作词。
- `python -m compileall finbot` 通过。
- `python -m finbot.cli.build_phase4_brief --clear-existing` 可生成报告并落库。

## Phase 4.1 Research Review Council

目标：

- 在 P4 watch item 之后增加多角色研究复核/讨论/仲裁层。
- 使用 deterministic reviewer modules 模拟 Evidence Auditor、Macro Context Analyst、Market Context Analyst、Skeptic Reviewer、Policy Risk Reviewer 和 Chair Arbiter。
- 生成每个 role 的 verdict、challenge / rebuttal / consensus 结构，并将结果落库。
- 保持 research-only，不输出交易信号、买卖、仓位、目标价或价格预测。

范围：

- `finbot/research/review_council.py`
- `finbot/cli/build_phase41_council.py`
- `finbot/storage/sqlite_store.py`
- `finbot/cli/status.py`
- `docs/10-phase41-research-review-council.md`
- `tasks/current.md`

非目标：

- 不接交易 API。
- 不引入自由 Agent swarm。
- 不新增外部 LLM 调用。
- 不把 Agent verdict 当事实源。
- 不替代 P3 `ResearchCardValidator`。

验收标准：

- 新增 `research_review_verdicts` 表。
- 新增 `research_councils` 表。
- 新增 `build_phase41_council` CLI。
- 每个 watch item 至少生成 5 个 reviewer verdict 和 1 个 chair verdict。
- Council payload 包含 role verdicts、discussion rounds、chair consensus 和 policy gate。
- policy gate 能阻止交易动作词。
- `python -m compileall finbot` 通过。
- `python -m finbot.cli.build_phase41_council --clear-existing` 可生成报告并落库。

测试方式：

```powershell
python -m compileall finbot
python -m finbot.cli.build_phase4_brief --clear-existing
python -m finbot.cli.build_phase41_council --clear-existing
python -m finbot.cli.status
```

## Phase 7 Web 服务化

目标：

- 将 FinBot 服务化为标准 Web Service。
- 后端使用 FastAPI / Uvicorn。
- 前端创建 React + TypeScript + MUI 工程。
- Web 层统一承载状态、报告、后台任务、研究流水线、交易建议、代理诊断和自动循环；账户权限预检已移除，系统只抓取公共行情。
- 系统面向用户的界面与服务说明只支持中文。
- 系统配置界面支持热更运行时配置、代理策略和任务默认值。
- 系统配置使用一级菜单下的二级子菜单，不把所有配置堆在同一界面。
- AI 配置支持站点、密钥、模型、环节绑定和提示词模板，并支持从上游 `/models` 刷新模型列表。

范围：

- `finbot/web/`
- `finbot/config/runtime_config.py`
- `finbot/config/ai_sites.py`
- `finbot/cli/serve_web.py`
- `web-ui/`
- `config/runtime_config.example.json`
- `config/ai_sites.example.json`
- `pyproject.toml`
- `.gitignore`
- `docs/14-phase7-web-service.md`

非目标：

- 不接真实交易执行 API。
- 不让 Web 层复制 domain 业务规则。
- 不做生产鉴权体系；下一步单独补。

验收标准：

- `python -m finbot.cli.serve_web` 能启动 FastAPI / Uvicorn。
- `/docs` 可访问。
- `/api/v1/status` 可返回系统状态。
- `/api/v1/config` 可返回配置 schema 与当前配置状态。
- `PUT /api/v1/config` 可写入运行时配置或代理策略，并在下一次请求/新任务中生效。
- `/api/v1/ai/config` 可返回 AI 站点、环节绑定和提示词配置，且不回显密钥。
- `POST /api/v1/ai/config/models/refresh` 可按站点和协议刷新模型列表。
- 后台 job 可提交并查询。
- 前端生产构建通过，并由后端托管。
- 桌面和移动端页面可正常渲染，中文 UI 不溢出。
- 密钥、代理池和 VLESS 订阅地址不从 API 明文回显。
- 系统配置二级菜单在桌面端位于左侧一级菜单下方，在移动端位于一级顶部菜单下方。

测试方式：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
cd ..
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
```

## Phase 8 Autonomous Research & Advisory Loop

目标：

- 将系统从手动触发升级为可定时运行的自主研究闭环。
- 自动串联信源采集、传统清理、AI compression、研究卡片、P4 简报、P4.1 复核、交易所公共行情、产品筛选和建议输出。
- Web 端展示调度状态、最近步骤、错误态和最新产品建议。
- 保持 advisory-only，不让 AI 调用真实交易执行 API。

范围：

- `finbot/autonomous/`
- `finbot/storage/sqlite_store.py`
- `finbot/config/runtime_config.py`
- `finbot/web/service.py`
- `finbot/cli/serve_web.py`
- `web-ui/src/App.tsx`
- `web-ui/src/api.ts`
- `web-ui/src/types.ts`
- `web-ui/src/utils.ts`
- `config/runtime_config.example.json`
- `docs/15-phase8-autonomous-research-loop.md`

非目标：

- 不引入 RAG / 向量库。
- 不引入外部队列或分布式 worker。
- 不开放下单、撤单、转账、改仓 API。
- 不绕过 Firecrawl 代理池和交易所代理策略。

验收标准：

- 新增 `autonomous_loop_runs`、`autonomous_loop_steps`、`autonomous_loop_artifacts`。
- `AutonomousResearchLoopRunner` 可持久化运行、步骤和建议产物。
- `AutonomousLoopScheduler` 可热读取配置，并支持手动触发。
- `/api/v1/autonomous/status` 返回调度状态、最近运行和最新建议。
- `/api/v1/autonomous/run-now` 可触发后台 P8 循环，已有运行时返回冲突。
- 前端新增“自动循环”一级菜单。
- 系统配置的“研究与交易”域包含自动循环配置项。
- `python -m compileall finbot`、`python -m unittest discover -s tests`、`npm run build` 通过。

测试方式：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
cd ..
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
```

## Phase 8.1-8.7 AI Debate Council And Decision Synthesis

目标：

- 将 P8 从“自动串联研究与 operator workbench”升级为完整自主研究与建议闭环。
- 在产品候选之后加入真实 AI 多 Agent 辩论、Chair 合成和本地风险门禁。
- Web 端展示 AI 辩论、AI 决策、证据引用、门禁原因和最新产品建议。
- 保持 advisory-only，不开放真实交易执行 API。

范围：

- `finbot/autonomous/product_candidates.py`
- `finbot/autonomous/ai_debate.py`
- `finbot/autonomous/runner.py`
- `finbot/autonomous/product_selector.py`
- `finbot/config/ai_sites.py`
- `finbot/config/runtime_config.py`
- `finbot/storage/sqlite_store.py`
- `finbot/web/service.py`
- `web-ui/src/App.tsx`
- `web-ui/src/types.ts`
- `web-ui/src/utils.ts`
- `config/runtime_config.example.json`
- `config/ai_sites.example.json`
- `docs/15-phase8-autonomous-research-loop.md`
- `tests/test_ai_debate.py`
- `tests/test_autonomous_loop.py`

非目标：

- 不允许 AI 直接或间接调用下单、撤单、转账、改仓接口。
- 不把 AI debate 输出当作事实源。
- 不引入 RAG / 向量库 / 外部分布式队列。

验收标准：

- 自动循环步骤包含 `product_candidates`、`ai_debate`、`trade_synthesis`。
- AI 站点配置新增 `ai_debate` 和 `ai_trade_synthesis` task，可配置站点、模型和提示词。
- 新增 `ai_debate_councils`、`ai_debate_messages`、`ai_trade_decisions` 表。
- `ProductRecommendationSelector` 优先使用 AI 决策，AI 不可用时回退 operator workbench。
- 风控门禁强制 `execution_allowed=false`，低置信度或缺研究确认时方向建议降级为 `WATCH`。
- Web 自动循环页展示最近 AI 辩论和 AI 决策。

测试方式：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```

## P0 Council / Hybrid Universe / Resident Worker

状态：完成（2026-07-10）

目标：

- 实现用户可配置角色、AI 站点、模型、提示词和发言顺序的真正多轮 Council。
- 建立交易产品目录与 Hybrid Universe，修复猜测式 symbol 映射。
- 将自动循环迁移到 DB 驱动的常驻 Worker，并完成同轮闭环验证。

规范：

- `docs/requirements/16-p0-council-universe-worker.md`
- `docs/decisions/001-p0-council-universe-worker.md`

验收：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```

运行态证据：

- manual-worker request：`5d3e580e40eb4441b9430d53c7ba0f7c`，状态 `succeeded`。
- autonomous run：`4efc16e225e36d00456fe70694923163`，9 个步骤全部 `passed`。
- instrument catalog：Gate spot 2,203 条；Universe run `21622fa616f834f5626c830c902ef9ea` 选择 12 条。
- Council：`dfbd2efc606cfaa71cdfb66858c72186`，3 个分析阶段各 4/4 完成，Chair 后共 13 条消息；第 2 轮以后 9/9 消息包含前序引用。
- 最终输出 3 条 `WATCH` 建议，`execution_allowed=false`。
- Windows Scheduled Task `FinBot-Worker` 已注册并运行，Worker lease 由任务进程持有。

## P1 Evaluation / Portfolio Risk / AI Governance

状态：完成（2026-07-11）

目标：

- 内置可复用 Council 角色预设，并复用已有 AI site/model/key 解析链。
- 建立历史建议 outcome、绩效/回撤、置信度校准和模型/实验对比。
- 建立组合相关性、集中度和极端行情压力测试。
- 建立 Prompt/model 版本、token/成本预算、A/B 分流和 claim 证据覆盖审计。

规范：

- `docs/requirements/18-p1-evaluation-risk-ai-governance.md`
- `docs/decisions/002-p1-evaluation-risk-ai-governance.md`

验收：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```

运行态证据：

- scheduled-worker requests：`379bd32bf9774abba221b5008f202af3`、`2133f55a4dd84bbc92cadda09bc30827`，均为 `succeeded`。
- 最新 autonomous run：`f4fdedff8d7c925a315381ad1d39fb0c`，12 个步骤全部 `passed`；此前 run `8635d9425afeb1fd43382f8cb4d45e94` 同样 12/12 通过。
- Council：`ec1e7dbe546cc6d25914132eaf52cbec`，独立分析、交叉质询、立场修订各 4 条消息，Chair 1 条，共 13 条；后续轮次消息均保留前序引用。
- AI governance：`037bddfb2d7d840e90e1d4009fb591f0`，13/13 调用完成，累计 122,130 token，94 个 claim 的证据覆盖率为 100%；因未配置模型费率，成本明确标记为 unknown。
- recommendation evaluation：`23342b5b050c7131b31c6648f6d03336`，12 个历史样本中 1 个已评估、2 个因缺少允许时间窗内的退出行情标记为 `insufficient_data`、9 个仍处于成熟期；当前没有方向性样本，因此不生成命中率或收益指标。
- portfolio risk：`b5201430ccbf54954a1740b5d22d5d88`，当前建议均为 `WATCH`，因此明确标记为无方向暴露。
- Windows Scheduled Task `FinBot-Web` 与 `FinBot-Worker` 均已注册并运行，允许电池供电启动，失败重启次数为 999，且无执行时长上限。
- Playwright 已完成桌面与 390px 窄屏 smoke；自动循环、角色预设和 AI A/B 实验页面均可渲染，窄屏无页面级横向滚动。

## P2 Defaults / Readiness / Operations

状态：完成（2026-07-11）

目标：

- 为 DeepSeek 与 MiMo 当前默认模型内置官方 token 费率和来源元数据。
- 提供推荐、省成本、深度研究三组一键配置档案。
- 提供首次启用就绪度，减少用户手工填写并明确剩余阻断项。
- 让历史 AI 调用能够在型号匹配时按当前费率生成成本估算。

规范：

- `docs/requirements/19-p2-defaults-readiness-operations.md`
- `docs/decisions/003-p2-default-profiles-pricing-readiness.md`

验收：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```

## P2.1 AI 调度工作流

状态：完成（2026-07-11）

目标：

- 提供可添加、删除、拖动和连线的 Council 工作流编辑器。
- 让每个 Agent/Chair 节点直接配置现有 AI 站点、模型和角色提示词。
- 让 DAG 连线真实决定同轮拓扑调度、上下文传递与 Chair 直接输入。
- 兼容已有 `roles + phases + chair` 模板和真正多轮辩论。

规范：

- `docs/requirements/20-p21-ai-workflow-editor.md`
- `docs/decisions/004-p21-versioned-council-workflow-dag.md`

验收：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```

运行态证据：

- AI 配置已升级并持久化为 v5；默认 `product_advisory` 为 6 个节点、7 条连线。
- scheduled run `87323bd056b6bb98ae37a7b2b83a663a` 由常驻 Worker 执行，12/12 步全部 `passed`。
- Council `343be64d11495ecd6fadc4cb5820cdda` 共 13 条消息；每轮均为“看多/看空/市场结构并行 -> 风险控制”两层，Chair 只直接引用风险控制员最终消息。
- 三轮风险控制员的 `reply_to` 数量为 3、7、11；第二轮首层 round-robin 的引用数量为 4、5、6，证明跨轮历史和同层轮流上下文均生效。
- AI governance `9cf891f1e285044b7b8887a9a266bd16`：13/13 调用成功，106,816 token，估算成本 `$0.03804221`，99 个 claim 证据覆盖率 100%。
- Browser QA 覆盖 1536x1024 与 390x844；新增/撤销、节点拖动/撤销、断线校验、端口重连、保存回读均通过，console 无 warning/error，窄屏无页面级横向溢出。
- `FinBot-Web`、`FinBot-Worker` 均为 `Running`；v5 配置快照为 `data/backups/ai-sites-workflow-v5-20260711-120245.json`。
- 界面概念图由 sub2api image generation 生成：`output/imagegen/finbot-ai-workflow-concept.png`。

## P2.2 人类可读的运营与 AI 决策体验

状态：完成（2026-07-11）

目标：

- 重构总览和自动循环用户动线，优先呈现系统健康、最新结论、注意事项和下一步。
- 区分候选评分与门禁后置信度，将 AI 结果改为中文结构化决策摘要。
- 按真实轮次展示多 Agent 辩论，主席结论优先，模型原文按需展开。
- 补齐交易建议、研究流水线、报告查看的最近结果、反馈、空态和移动端体验。

规范：

- `docs/requirements/21-p22-human-centered-operations-ux.md`

验收：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```

设计参考：

- `output/imagegen/finbot-ux-operations-overview.png`
- `output/imagegen/finbot-ux-ai-analysis.png`

运行态证据：

- 最新 scheduled run `3c9d554dac280e2f4c8bf58f733f4f61` 为 `passed`，12/12 步完成，输出 3 条最终建议。
- 最新 Council `4f431b45e106b9d0fa7330de3dcc4d2d` 为 `passed`，三轮分析加主席仲裁共 13 条消息。
- 总览首屏已显示系统健康、最近完成、12/12 进度、下次运行、待补证据提示和最终产品建议。
- 自动循环改为本轮结果、运行过程、多轮辩论、风险与治理四视图；主席结论和三轮角色对比均通过浏览器验证。
- 报告页自动载入结构化中文摘要，确定性建议固定英文已本地化，原始 JSON 默认折叠。
- AI 站点从 2 个长表单同时展开改为单站点展开，桌面滚动高度由约 2820px 降至约 1700px。
- 自动循环移动端页面高度由旧版约 5995px 降至约 2255px，390x844 无页面级横向溢出；桌面 1536x1024 无横向溢出。
- 浏览器桌面/移动控制台无 warning/error；`FinBot-Web`、`FinBot-Worker` 均为 `Running`。
- `python -m compileall finbot`、57 项 `unittest`、`npm run build` 均通过；最终主入口约 103 kB，MUI 与 React Flow 已独立分包，构建无体积或循环 chunk 警告。

## P3.5 即时研究模式

状态：已完成（2026-07-11）

目标：

- 新增“即时研究”入口，用户通过一个聊天式输入框提交自然语言研究问题。
- 复用现有常驻 Worker 与完整闭环，让问题真实进入定向收集、清理压缩、行情分析、多 Agent 多轮辩论和产品建议。
- 运行开始即持久化关联请求与 `loop_run_id`，前端轮询展示排队、内层研究流水线、外层自动循环、逐条辩论消息和最终结果。
- 只展示模型已经返回的结构化观点、证据引用与角色消息，不展示或伪造隐藏思维链。

范围：

- API：即时研究提交、最近会话列表和单会话实时详情。
- Worker：即时请求优先排队、请求上下文透传、开始运行时绑定 loop。
- Pipeline：用户问题作为定向搜索 query，并进入 Council 角色与主席合成上下文。
- Web：问题输入、运行时间线、流程详情、AI 辩论、最终结果和最近会话。

非目标：

- 不新增第二套调度器或临时内存任务体系。
- 即时模式不提交模拟订单，不改变真实盘硬禁止策略。
- 不向用户暴露 provider 隐藏推理或内部 chain-of-thought。

影响文件：

- `finbot/autonomous/worker.py`、`finbot/autonomous/runner.py`、`finbot/autonomous/ai_debate.py`
- `finbot/orchestration/pipeline.py`、`finbot/ingestion/scheduler.py`
- `finbot/storage/sqlite_store.py`、`finbot/web/service.py`
- `web-ui/src/App.tsx`、`web-ui/src/api.ts`、`web-ui/src/types.ts`、新增即时研究面板
- 后端测试、前端构建与浏览器 QA

验收标准：

- 输入 2-500 字问题可返回持久化 `request_id`，重复刷新页面后仍能查看会话。
- 请求排队、运行、成功、部分成功和失败状态均可读；运行中能看到当前步骤和已完成耗时。
- 定向采集步骤保存用户 query，AI 辩论消息可在 Council 完成前逐条出现。
- 最终页面展示主席结论、AI 决策、风险提示和产品建议；无写交易请求。
- 桌面 1536x1024 与移动 390x844 无页面级横向溢出，核心提交流程和视图切换可用。
- Python 测试、前端构建、HTTP smoke 和浏览器 smoke 通过。

设计参考：

- `output/imagegen/finbot-instant-research-concept.png`

测试方式：

```powershell
python -m compileall finbot
python -m unittest discover -s tests -v
cd web-ui
npm run build
```

运行验收：

- 两次真实即时研究均完成 `13/13` 个自动循环步骤和 `14/14` 个内层研究步骤。
- 每次会话持久化 `13` 条多 Agent 辩论消息、`3` 条 AI 决策和 `3` 条产品建议；`paper_execution` 为 `skipped`。
- 会话列表接口仅返回摘要，2 条记录响应体为 `990 bytes`；单会话详情按需读取并由约 `224 KB` 压缩至约 `96.6 KB`。
- `python -m unittest discover -s tests -v`：`79/79` 通过。
- `npm run build`：TypeScript 与 Vite 生产构建通过。
- Playwright smoke：桌面 `1536x1024`、移动 `390x844` 无页面级横向溢出，页面无 console warning/error。
- Web `/health` 返回 `ok`，Web 与 Worker 进程均常驻。

## P0-P1 产品闭环质量收口与 K8S 基线

状态：已完成（2026-07-12）

已完成：

- 修复无价格/主周期 K 线候选、代表合约选择、遗留运行恢复、运行状态与决策就绪度混淆。
- 贯通产品/Watchlist、即时研究、三轮多 Agent 辩论、人工复核、历史对比/重放/续跑、工作流版本治理、Shadow Portfolio 与效果反馈。
- 严格区分基本面确认与 `market-confirmed` 技术互证；技术互证要求至少两家 provider 同向、最低强度 0.60、价差不超过 1%，并保留未确认基本面风险。
- 新增 `sub2api` Responses 站点，默认 `gpt-5.6-luna`；模型列表与最小 Responses 请求实测通过，现有角色绑定保持不变。
- 新增 Dockerfile 与 `deploy/k8s/` 基线；SQLite 阶段固定单 Pod、`replicas=1`、`Recreate`、Web+Worker 共享 RWO PVC。
- 新增独立非 root `finbot-egress-proxy`，FinBot Pod 不绑定节点；代理通过 `infra.mnnu/location=sg` 固定到已验证新加坡出口。
- AI 辩论候选优先选择“可执行 + 已确认 + 产品去重”，主席明确把有效 `market-confirmed` 视为满足研究确认，基本面待补只做风险披露。
- 修复禁用 bridge 时仍下载无关 VLESS 订阅、风险 warning 被当作 blocked，以及建议来源 provider 被误当成执行仓位 provider 的问题。

验证：

- `python -m unittest discover -s tests -v`：112/112 通过。
- `python -m compileall -q finbot`：通过。
- `npm run build`：通过；仅 MUI vendor 约 506 kB 的非阻断提示。
- 浏览器：1536/1280/390 无页面级横向溢出；产品到研究、人工复核、历史、反馈、工作流与新增 AI 站点均可见；console 无 error/warning。
- 最终研究闭环 `68c6584a7ce3ffd4bcd79bf80c7e7f1d` 生成 6 条 `market-confirmed` 方向性建议；DOGEUSDT SELL 通过人工复核、风险 warning 和 AI Governance。
- 模拟执行 `c3957c42717a374de81ecf53d1221c55` 为 `passed`：Gate TestNet `filled`、Bybit Demo `submitted` 且后续持仓查询确认；两家名义均低于 100 USDT，数据库仅 2 条唯一 adapter 记录。
- Shadow Portfolio 为 `ready`；`paper_execution.submit_orders=false`，真实盘仍硬禁止，模拟仓位保留开放。

后续部署：

- 在 K8S overlay 配置镜像仓库、StorageClass、Ingress/TLS 和 Secret；给当前新加坡节点补 `infra.mnnu/location=sg` 后再正式 `kubectl apply -k`。
- 当前只完成目标集群 client dry-run，未创建 FinBot namespace、Pod、PVC 或 Service；生产部署与备份恢复演练不计入本次 P0-P1 功能收口。

## P1 模拟交易账户与盈亏面板

状态：已完成（2026-07-12）

目标：

- 为 Gate TestNet 与 Bybit Demo 提供统一的只读账户视图，清楚展示账户权益、可用余额、保证金、当前未实现盈亏、可选时间区间已实现盈亏与持仓明细。

范围：

- 在既有模拟交易客户端上增加只读账户、持仓和已实现盈亏查询。
- 新增多交易所账户快照聚合服务与 `GET /api/v1/exchange-accounts` API；支持全部历史、24 小时、7 天、30 天和自定义区间，单个交易所失败不阻断其他交易所。
- 新增“交易账户”控制台页面，支持区间切换、自定义起止日期、手动刷新、汇总指标、交易所状态、持仓表格、空态和错误态。
- 所有账户请求继续遵守 Gate TestNet、Bybit Demo 固定 host 与现有交易所代理策略。

非目标：

- 不接入 Gate/Bybit 主网私有 API，不允许真实交易、转账、杠杆修改、撤单或账户设置变更。
- 不把充值、划转或初始模拟资金差额解释为交易收益。
- 不新增账户历史数据库；本期按交易所可用的累计收益字段和历史流水查询计算区间收益，并明确完整性与截断状态。

影响文件：

- `finbot/exchange/gate_testnet.py`、`finbot/exchange/bybit_demo.py`、`finbot/exchange/runtime.py`
- 新增账户快照领域/聚合模块，修改 `finbot/web/service.py`
- `web-ui/src/App.tsx`、`web-ui/src/api.ts`、`web-ui/src/types.ts`、新增交易账户面板
- `tests/test_paper_execution.py`、`tests/test_web_service.py` 与前端构建/浏览器 QA

验收标准：

- API 不返回 API key、secret、签名、原始鉴权 header 或未经筛选的交易所响应。
- Gate 与 Bybit 均规范化为 USDT 账户指标和统一持仓字段；盈亏正负方向一致。
- 单个交易所超时、代理失败或鉴权失败时返回该 adapter 的可读错误，其他 adapter 仍正常展示。
- 前端明确区分“当前未实现盈亏”和“所选区间已实现盈亏”；仅在全部历史口径下展示累计已实现与当前未实现的观察合计，并显示区间与数据时间。
- 未配置凭据、无持仓、部分失败和全部失败均有稳定 UI；桌面与移动端无页面级横向溢出。
- Python 单元测试、前端生产构建、HTTP smoke 与浏览器 smoke 通过。

测试方式：

```powershell
python -m unittest discover -s tests -p "test_exchange_accounts.py" -v
python -m unittest discover -s tests -p "test_paper_execution.py" -v
python -m unittest discover -s tests -p "test_web_service.py" -v
python -m compileall -q finbot
cd web-ui
npm run build
```

实现结果：

- 新增统一账户快照模型、盈亏区间解析和多交易所部分失败聚合；区间支持 `all`、`24h`、`7d`、`30d`、`custom`。
- Gate TestNet 通过 futures account、positions、account book 计算权益、持仓、当前未实现盈亏和所选区间已实现盈亏。
- Bybit Demo 全历史使用 USDT `cumRealisedPnl`，选定区间按官方 7 天限制自动分片查询 Closed PnL。
- 新增 `GET /api/v1/exchange-accounts` 与“交易账户”页面；前端包含汇总、交易所状态、持仓、区间切换、自定义日期、加载/空/部分失败/阻断状态。
- 账户 API 只返回白名单字段，错误信息脱敏并中文化；主网私有 API 和所有写请求继续禁止。
- 账户查询与模拟下单只启动 exchange bridge，不再无关加载 Firecrawl bridge；其他 `ProxyRuntime` 调用保持兼容。

验证：

- `python -m unittest discover -s tests -v`：`118/118` 通过。
- `python -m compileall -q finbot`：通过。
- `npm run build`：通过；仅保留既有 MUI vendor 约 506 kB 非阻断提示。
- Live API：Gate TestNet 全历史与 24h 查询成功，返回 1 个 DOGE_USDT 模拟持仓；Bybit Demo 因当前代理出口被地区策略拦截，聚合状态正确为 `partial`。
- Playwright：桌面 `1536x1024` 与移动 `390x844` 均无页面级横向溢出；移动端使用持仓摘要，console 无 warning/error。
- QA 服务运行于 `http://127.0.0.1:8781/`；未改动当前 `8780` 服务。

遗留风险：

- Bybit Demo 账户读取需更换为当前可访问 Bybit 的代理出口；代码与 UI 已正确处理该 adapter 的独立失败。

## MiMo2API 账户池替换官方渠道

状态：已完成（2026-07-12）

目标：

- 将 FinBot 的 MiMo 官方 API 入口替换为集群内已部署的 MiMo2API 账户池。
- 先完成所有 AI 任务统一切换到 `mimo-v2.5-pro` 的基线验证；后续任务可在此基础上恢复混合模型编排。

范围：

- 扩展 AI task binding 的 `reasoning_effort` 契约及前端配置控件。
- 修正 MiMo2API Chat 请求字段，贯通信息压缩、辩论与建议合成。
- 更新代码默认配置、私有启动配置、Kubernetes Secret 和在线 PVC。

非目标：

- 不删除 DeepSeek/Sub2API 站点定义，不修改交易所、采集代理或模拟交易配置。
- 不把任何 API key、账户 token 或管理员凭据提交到 Git。

验收标准：

- 该阶段在线配置中只有 MiMo 站点启用；全部任务绑定、角色和主席均为 `chat + mimo-v2.5-pro`，思考等级非关闭状态且无备用站点。
- MiMo2API `/v1/models` 与最小 Chat 请求成功，真实请求携带思考开关。
- Python 测试、前端构建、CI 镜像发布、Argo CD 同步和在线调用验证通过。

实现结果：

- MiMo 站点默认改为 `https://mimo2api.mnnu.eu.org/v1`；DeepSeek 和 Sub2API 保留但默认禁用。
- AI 配置升级为 v6，task binding 与 A/B variant 均支持 `provider_default/none/minimal/low/medium/high/xhigh`。
- 所有默认任务、内置委员会角色和主席统一使用 `chat + mimo-v2.5-pro`，无静默备用站点。
- MiMo2API Chat 使用 `reasoning_effort`；DeepSeek Chat、通用 Chat 与 Responses 分别使用各自兼容字段。

验证：

- `python -m unittest discover -s tests -v`：`200/200` 通过。
- `python -m compileall -q finbot`、`python scripts/secret_scan.py`：通过。
- `npm run build`：通过，仅保留既有 MUI vendor chunk 大小提示。
- MiMo2API 真实最小调用返回 `200`、模型 `mimo-v2.5-pro`，账户池当前包含 11 个账户。

## 混合模型分析与反思型最终执行机器人

状态：进行中（2026-07-12）

目标：

- 分析流程混用 DeepSeek、MiMo 和 `gpt-5.6-terra`，最终执行由 `gpt-5.6-sol/xhigh` 独立把关。
- Gate TestNet 与 Bybit Demo 允许无人值守自动批准和提交，Mainnet/Live 继续硬阻断。

范围：

- 新增 `ai_execution_robot` task binding 和 `execution_robot` 自动循环步骤。
- 实现 Sol 初审与反思终审两阶段调用、独立 invocation/Token/成本审计和 fail-closed。
- 升级 AI 配置到 v7，迁移旧 Sub2API 模型清单并贯通模型思考强度。
- 调整 K8S 运行配置、生产 readiness、测试网自动提交策略和 GitOps 部署。

非目标：

- 不允许 AI 创建订单参数、改变方向/数量/杠杆或直接调用交易所。
- 不接入或放开任何真实盘 host，不实现资金划转、充值、提现或主网私有 API。

验收标准：

- 初审和反思终审均使用 `responses + gpt-5.6-sol + xhigh`；任一阶段失败时零订单。
- 终审只能批准输入中的原始 `decision_id`，漏审项自动拒绝。
- 自动提交不要求 `human_review_status=approved`，但必须通过 Portfolio Risk、AI Governance、置信度、名义价值、映射和重复持仓门禁。
- 生产 readiness 在“自动提交 + 无人工复核 + 未启用执行机器人”时返回阻断。
- 全量测试、前端构建、Secret 扫描、CI 镜像、Argo CD、线上健康检查和真实 Sol 最小调用通过。

测试方式：

```powershell
python -m unittest discover -s tests -v
python -m compileall -q finbot
python scripts/secret_scan.py
cd web-ui
npm run build
```
