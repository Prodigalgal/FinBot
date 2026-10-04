# 指定商品、本地模拟和前瞻假设发布验收

核对日期：2026-10-04，文中运行时间使用 UTC，界面显示按用户时区转换。

## 发布与当前范围

- 业务代码候选：`5c23ab65a5a01612d9059494dcd7b892a3283d50`；主功能提交 `d2706af`，随后修正真实 PostgreSQL 旧计数断言、OpenAPI 重复 YAML anchor 和新增任务的默认并发配置。
- 完整 CI：[37172515409](https://github.com/Prodigalgal/FinBot/actions/runs/37172515409)，verify、四镜像构建/扫描/签名和 update-gitops 全部成功。
- FinBot GitOps 提交：`b628f5651bfe1e40379c06878c919ff30108c269`。
- 2026-10-04 03:04 后线上核对：Argo CD `Synced/Healthy`，operation `Succeeded`；Backend/Web/Quant/Browser Worker 均为上述候选镜像，四 Pod 均 `Running`、Ready、0 重启。
- Liquibase `76/76`；070、071、072 分别在 03:03:39、03:03:40、03:03:41 完成。后台撮合、账户同步、订单对账和假设/预测复核实际完成任务。

用户最后决定用 QQQUSDT 替代 NAS100.s 作为纳指相关研究商品。本轮默认研究名单共 8 个：BTCUSDT、ETHUSDT、DOGEUSDT、XAUUSDT、SKHYNIXUSDT、SNDKUSDT、MUUSDT、QQQUSDT，全部固定 BYBIT 的精确 instrument 映射。默认自选保留原有 AAPL/SOL 为 MONITOR，共 10 条，不删除历史记录。NAS100.s 的 CFD 目录元数据保留，未加入默认自选且行情未就绪。

QQQ 基金跟踪 Nasdaq-100，适合用作这部分研究对象；QQQUSDT 是 Bybit USDT 永续，独立使用其报价、数量单位、标记价和资金费，不能把其行情写入 NAS100.s 或合并两者历史收益。来源：[Invesco QQQ 说明](https://www.invesco.com/qqq-etf/en/about.html)、[Bybit TradFi 接入](https://bybit-exchange.github.io/docs/v5/tradfi-integration)。

## Changelog

### 商品与研究入口

- `UserSelectedResearchScopeQuery`、`ResearchLaunchService`、`UserSelectedResearchRunner`：默认自选显式映射作为入口；即时研究受理时冻结一个商品，空名单在创建工作流前拒绝；定时研究逐商品串行，一个失败记录后继续，取消停止后续商品。
- `/api/v2/research/scopes` 与 `ResearchPage`：返回和选择真实指定映射；旧客户端未传范围时使用首个指定商品，ANALYSIS_ONLY 聊天仍保持兼容。
- `071-bybit-cfd-catalog.sql`：保留 NAS100.s 原始大小写与 CFD 类型；修正既有股票/指数商品分类，保持既有 product/instrument ID。

### 本地模拟盘

- 新增 paper domain/application/JDBC 模块与 `070-bybit-tradfi-local-paper.sql`：独立虚拟 USDT 账本，初始 10,000 USDT；计划、成交事件、余额、费用、持仓与游标持久化。不会加载主网交易凭据，不创建交易所私有订单。
- `JdkBybitPaperMarketGateway`：读取 LIVE 公共合约、盘口、标记价、已结束分钟 K 线和历史资金费；盘口时间使用 matching engine `cts`，拒绝过期、未来和顺序异常的报价。缺分钟、标记价或资金费时停止推进。
- 账户锁、版本校验、事件去重和 NUMERIC 精度校验保护并发撮合与资金账本；下单前 K 线不得回填成交，同柱先后不明及跳空采用保守规则。
- `LocalPaperController`、OpenAPI 与 `TradingPage` 的“本地模拟”页签：查看账户/计划/记录，暂停恢复、取消挂单和明确模拟平仓；既有 Demo、OMS 和历史页保留。
- `LOCAL_PAPER_MATCHING` 每 10 秒运行，默认并发 1；账户暂停或行情不满足要求时拒绝新本地计划，不回落到交易所私有执行。

### 前瞻假设

- `OpportunityHypothesis` 与协议 codec：proposal/revision 支持可选因果链、必要条件、催化、已定价观察、替代情景、触发、失效、后续核对和缺失数据；标记 OBSERVATION/ESTIMATE/PROXY/INFERENCE，旧输出没有该字段仍兼容。
- `072-forward-hypothesis-ledger.sql`、`HypothesisService`、JDBC：仅导入已揭示、当前指定 LIVE 研究候选；来源去重，首次正文/概率/提出时间/证据截止/期限保留，后续修订与状态变化追加审计。CAS 冲突返回 409。
- 假设生命周期为待验证、观察、临近催化、确认、模拟验证、完成；反证/过期可终止。假设状态不能绕过共识、执行审核或硬风控。
- 复用现有 300 秒 `FORECAST_EVALUATION` 调度，新增 hypotheses/history/status 管理 API 和复核页看板。

### 发布修复与兼容

- PostgreSQL 迁移测试更新预置 product、schedule、CFD 不可执行商品的断言；新增真实事务/重复事件/并发/CAS/缺数据场景验证。
- OpenAPI 新路径与 schema 展开重复 YAML anchor，保持接口结构语义不变。
- `application.yaml` 增加 `FINBOT_WORKER_MAXIMUM_LOCAL_PAPER_MATCHING` 默认 1；`ApplicationYamlTest` 从真实生产 YAML 绑定全部任务限制，防止测试构造器掩盖部署缺配置。
- 所有新增表为追加迁移；旧聊天、研究输出、Demo 账本、OMS 入口及历史记录保留。新增 CFD 枚举和任务类型对老版本应用的读取存在兼容要求，不能直接降回不认识这些值的二进制。

## 验证证据

| 验证 | 结果 |
|---|---|
| 完整 Java CI XML | 121 suites、484 tests，0 failures/errors、0 skipped |
| 新本地账本/假设 PostgreSQL 场景 | 6 个真实数据库测试通过；包含并发和回滚 |
| 前端组件、构建、契约、浏览器 smoke | CI 全部通过；本地 38 tests，106 paths/125 controller operations |
| 新 worker YAML 与 worker runtime | 本地定向测试通过，CI 完整启动通过 |
| 生产 readiness | HTTP 200，UP |
| 未认证本地模拟账户 | HTTP 401 |
| 生产管理员会话 | 认证成功；8 个指定 BYBIT scopes 与本地账户/假设 API 均 200 |
| 本地账户初始快照 | LOCAL_PAPER，cash/equity/available 10,000 USDT，0 挂单/持仓/费用 |
| 调度 | matching 10 秒、forecast 300 秒；scheduled research 保持 disabled |
| 实盘边界 | execution.live.enabled=false；截至研究启动时当日 OMS 新订单 0 |

CI 包含真实登录与多工作区/窄屏浏览器验收。生产端实际完成认证 API 验收；本次生产浏览器只核对登录页，未重新完成验证码和登录，因此不将 CI 浏览器证明写成生产 UI 已认证端到端。

## 一轮真实研究

- 商品：BYBIT QQQUSDT，LIVE，1 小时 K 线、24 小时预测期限；输入要求区分事实/估计/代理/推断、保存可证伪前瞻假设，允许 NO_ACTION。
- 受理：2026-10-04 03:07:25 UTC。
- run：`run_00000mut8pxez_5147b43ec6942f465b9b`。
- task：`task_00000mut8pxst_ea92fd5cfeb6184ea291`，幂等键固定，未重复创建研究。
- task payload 已直接核对冻结 QQQUSDT / instrument_legacy_b9f01cfccd0068f1c6d409cd / BYBIT / LIVE。
- 初期 LongCat 两个临时不可用调用经现有重试恢复；首篇文档的两次清洗及两次压缩调用完成，不代表全部证据压缩完成。首篇 DeepSeek 验证从 03:10:26 运行至 03:30:27 后以 `AI_INVOCATION_TIMEOUT` 失败，03:30:33 开始重试；节点配置为每次 1200 秒、最多 3 次，随后可使用现有 fallback。
- 截至本次能力审计，task 心跳正常，run 仍为 ACCEPTED、尚未进入 SDB-SCA 面板；0 条新假设、0 条本地模拟计划、当日 OMS 新订单 0。不能将受理或单次 AI 调用完成视为研究终局。

## 生产能力复核

- 03:39 后再次核对：四核心 Pod 均 Running/Ready、0 重启；Argo CD Synced/Healthy，GitOps revision 未漂移。
- 默认 v15：五个逻辑角色，各两席位，角色内 primary/fallback 不重叠；清洗两个节点同为 LongCat，压缩两个节点同为 MiMo，不能将预处理称为异构共识。
- Bybit Demo USDT 快照 03:39:00 持续更新；本地账户 03:39:30 仍为 10,000 USDT，费用/资金费/模拟交易均为 0。
- 情报数量、相关性、上下文截断和预测样本的独立审计见[系统能力审计](2026-10-04-system-research-capability-audit.md)。研究输入问题尚未修复；发布健康不代表研究质量充分。

## 遗留范围与回滚

- NAS100.s 未接行情、未验证 MT5 只读连接、未启用 CFD 模拟。用户已设置只读密码，但随后决定本轮不安装 MT5 并改用 QQQUSDT；密码未加入 Git、报告、镜像或 FinBot 运行配置。
- 宏观经济日历公布值/修订、一致预期快照、自动条件验证和前瞻提前量/误报率/校准统计属于后续；本轮假设保存不构成预测有效性的证明。
- 股票拆分/反拆后仓位、价格、数量的公司行动调整尚未实现；发生此类公告时须暂停相关模拟新单并单独处理已有仓位。停止更新时的旧盘口可由时间检查阻断，但不能据此宣称拆分恢复后的账本已自动调整。
- 只校验引用在原始研究 artifact 中的结构与关联，尚未覆盖每个原始数据源发布版本的历史可知性审计。
- 如需停止新能力，先通过 `/api/v2/trading/local-paper/account` 的 expectedVersion 关闭 ordersEnabled，并关闭 `schedule_local_paper_matching`；保留事件账本、持仓、hypothesis/revision。处理已有仓位时使用现版本受控操作。若需要二进制降版，必须先验证 CFD 和 LOCAL_PAPER_MATCHING 契约兼容，不能直接用旧 `0c5f401` 镜像回退。
- 生产默认自选修改前的备份保存在服务器管理 private 目录，不写入仓库；可按需要恢复原 preferredInstrument/researchMode，账本和研究历史不删除。
