# P0 需求：AI Council、产品目录与常驻 Worker

## 目标

- 将当前固定四角色、单轮 fan-out 的 P8 AI debate 升级为可配置、可审计、可恢复的真正多轮 Council。
- 建立交易产品主数据和 Hybrid Universe，不再通过字符串拼接猜测交易标的。
- 将自动循环从 FastAPI 进程内线程迁移到常驻 Worker，使用 SQLite 请求、租约和心跳协调。
- 完成一次采集、清理、AI 压缩、研究、产品目录、行情、Council、产品筛选和建议发布的同轮闭环验证。

## 范围

- `finbot/council/`：通用 Council 配置、轮次编排、消息契约和执行后端。
- `finbot/instruments/`：产品目录、别名解析、目录同步和 Hybrid Universe。
- `finbot/autonomous/`：P8 接线、Worker、状态与兼容适配。
- `finbot/storage/sqlite_store.py`：Council、产品目录、Universe、Worker 请求/租约/心跳表。
- `finbot/config/ai_sites.py`、`finbot/config/runtime_config.py`：角色级 AI 绑定和 Universe/Worker 配置。
- `finbot/web/service.py`、`web-ui/`：配置、运行状态、Council 轮次和产品 Universe 展示。
- `tests/`：成功、失败、边界、恢复和兼容测试。

## 非目标

- 不开放下单、撤单、转账、调杠杆或其他私有交易 API。
- 不让 Council 角色自由调用网络工具；补证据通过既有 follow-up 队列完成。
- 不在每轮对全部交易产品拉取 K 线或调用 AI。
- 不在本阶段引入 PostgreSQL、外部消息队列或分布式 worker。
- 不把 AI 消息、压缩或最终建议当作事实源。

## Council 契约

- 用户可配置 2-12 个分析角色和一个 Chair；每个角色独立绑定 AI site、protocol、model、fallback sites 和 prompt。
- 默认流程为：独立分析 -> 交叉质询 -> 立场修订 -> Chair 合成 -> 本地 Policy Gate。
- 同一 phase 内支持 `parallel`、`round_robin` 和 `moderated` 调度；P0 实现前两种，保留 moderated 扩展点。
- 每条消息必须记录 round、phase、turn、message type、reply targets、provider/model、usage、耗时、结构化内容和错误。
- 第 2 轮及以后必须消费前序消息；测试需要证明消息数量和上下文引用，而不是只验证 `rounds` 配置值。
- 全局 policy prompt 不可由用户角色 prompt 覆盖。
- 达到最大轮数、预算、超时或 quorum 失败时必须确定性终止，不允许无限循环。

## 产品与 Universe 契约

- `canonical_products` 保存标准产品；`venue_instruments` 保存交易场所具体 instrument。
- instrument 记录 provider、symbol、market type、base/quote/settle、contract size、linear/inverse、expiry、精度、最小金额、active 和来源时间。
- 杠杆采用约束快照；不得把账户相关或按持仓规模变化的杠杆伪装成固定产品属性。
- 产品目录按 provider/market type 定时全量同步，深度行情只处理 Universe 入选项。
- Hybrid Universe 合并固定 watchlist、研究事件映射和市场排名结果，再按 active、quote、market type、流动性、点差和上限过滤。
- 任何候选必须匹配已存在 instrument；禁止自动追加 `USDT` 生成未经验证的 symbol。

## Worker 契约

- Web 只提交运行请求和展示状态；常驻 Worker 独占 scheduler lease 并执行自动循环。
- Worker 持久化 heartbeat、lease、next run、请求状态和最近错误。
- 多 Worker 同时启动时只能有一个持有 autonomous scheduler lease。
- shutdown 必须停止领取新任务、等待当前任务到达 checkpoint、关闭代理 runtime 和 SQLite 连接。
- 手动 API 请求和定时请求走同一队列及 runner。

## 验收标准

- Council 配置可通过 API/Web 创建角色、选择 site/model、编辑 prompt 和调整顺序。
- `roles * configured_rounds + chair` 的消息落库，且后续轮次包含前序 message references。
- `BZUSDT` 不会产生 `BZUSDTUSDT`；未知 asset 不进入交易候选。
- 至少一个 provider 的 instrument 目录可真实同步，Hybrid Universe 可产出已验证 instrument。
- Web 与 Worker 可独立启动；Web 重启不停止 Worker，Worker 状态可由 API 查询。
- 原 P8 报告和 advisory-only API 保持兼容。
- `python -m compileall finbot`、`python -m unittest discover -s tests`、`npm run build` 通过。
- 一次真实 scheduled/manual-worker 闭环中必需步骤不被跳过，内部全失败不能被报告为 passed。

## 回滚点

- `autonomous.universe_mode=fixed` 可回退固定 symbols。
- `worker.embedded_scheduler=true` 仅作为单进程开发兼容模式，生产默认关闭。
- 旧 `ai_debate_*` 表和 API payload 在迁移期保留，Council 通过适配器双写兼容字段。
