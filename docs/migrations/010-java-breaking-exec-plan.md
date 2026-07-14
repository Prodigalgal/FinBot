# Migration 010: Java 主系统 Breaking Migration 执行计划

状态：In Progress

日期：2026-07-14

## 切流目标

FinBot 的生产入口、常驻调度、业务状态、交易事实和用户界面全部切换到 Java 26 主系统；Python 仅保留无状态量化研究服务。迁移不建立旧 API、旧 Store 或运行时双写兼容层，历史数据通过可校验的停机导入进入新 Schema。

## 基线盘点

- 冻结的 Python 主系统包含 184 个模块文件、52 组测试、约 80 张运行表和近 90 个 HTTP 入口。
- 旧能力不能按 `finbot/web/service.py` 和 `SQLiteStore` 的单体结构逐文件翻译；新系统按领域聚合、应用用例和基础设施端口重新组织。
- React 已整体切换到 `/api/v2`，旧 `/api/v1` 页面和 API client 已删除。
- Java 26 主系统已覆盖身份、配置、目录、采集、研究、工作流、交易、账本和常驻任务；Python 仅保留无状态量化服务及独立代理控制面。

## 能力迁移矩阵

| ID | 旧能力与主要事实 | 新所有者 | 新契约与存储 | 切流验证 | 状态 |
| --- | --- | --- | --- | --- | --- |
| CAP-01 | 单管理员 Auth、PoW、数学验证码、Session | `identity` | `/api/v2/auth/*`；`admin_session`、`auth_challenge` | 匿名拒绝、挑战过期/重放/错误答案、Cookie 安全属性 | Completed |
| CAP-02 | runtime/setup/AI 配置和默认值 | `configuration` | `/api/v2/configuration/*`；版本化 `system_setting`、`ai_provider_profile`、`ai_model_rate` | Secret 不入库/响应/日志；乐观锁；开箱默认档案 | Completed |
| CAP-03 | 产品库、合约映射、别名、Watchlist、Universe | `catalog` | `/api/v2/products`、`/api/v2/watchlists`、`/api/v2/universes`；规范化产品与交易所合约表 | 搜索/分页、幂等关注、唯一映射、优先级确定性 | Completed |
| CAP-04 | Source、抓取任务、原始证据、清理、去重、事件提取 | `ingestion` | 异步采集命令和 artifact；source/evidence/document/event 表 | Firecrawl 无代理即 fail-closed；重试幂等；原文与清理结果可追溯 | Completed |
| CAP-05 | 行情、Kline、宏观日历、市场快照 | `market-data` | 批量 quote/candle/release 写入；时间范围 API | 批量 upsert、缺口/重复检测、provider 和时间口径可审计 | Completed |
| CAP-06 | AI Site、模型、费率、调用审计和预算 | `ai-governance` | provider port、credential reference、invocation ledger | Chat/Responses、`reasoning_effort` 含 `max`、流式 token、成本硬门禁 | Completed |
| CAP-07 | Workflow v2、自由 DAG、节点配置、版本发布/回滚 | `workflow-definition` | `/api/v2/workflow-definitions/*`；不可变发布快照和 checksum | 环检测、类型化端口、条件边、轮次、模型/厂商/思考等级逐节点配置 | Completed |
| CAP-08 | 真正多轮三家混合辩论、Chair、学习和记忆 | `agent-council` | run/message/challenge/revision/verdict 事件 | 每轮引用上一轮、节点拓扑决定上下文、Chair 独立仲裁、不暴露隐藏思维链 | Completed |
| CAP-09 | 定时/即时研究、历史、比较、重放、续跑、报告、反馈 | `research` | `202 + run_id`、可恢复 SSE、不可变 artifact | 收集到建议全链闭环；断线恢复；失败点续跑；即时模式可见阶段与辩论 | Completed |
| CAP-10 | 回测、指标、walk-forward、Monte Carlo、组合优化 | Python `quant-service` + Java `quant` port | OpenAPI 3.1 HTTP/SSE；Java 保存权威 run/event/artifact | 严格类型、取消/超时/断流、同幂等键重试、golden-master | Completed |
| CAP-11 | 杠杆/组合/执行风险、实验和归因 | `risk` / `strategy-lab` | policy/version、risk assessment、experiment/result | 费用/滑点/资金费/强平可解释；不制造样本不足指标 | Completed |
| CAP-12 | AI 决策、复核、Sol 初审+反思终审 | `trade-decision` | proposal 与 approved intent 强类型隔离 | 测试网自动审批；任一机器人阶段失败零订单；Live 永久硬阻断 | Completed |
| CAP-13 | Gate TestNet、Bybit Demo、代理路由 | `exchange` | Exchange port；固定测试环境 endpoint；credential/proxy reference | 签名向量、只读探针、地区/代理独立失败、禁止 Mainnet 私有写 | Completed |
| CAP-14 | OMS、提交、成交、撤单、对账、恢复 | `execution` | Order 聚合、事件状态机、idempotency key、reconciliation task | 重复请求零重复订单；非法转换失败；部分成交、断网和未知结果恢复 | Completed |
| CAP-15 | 账户、持仓、PnL、订单/成交永久历史 | `trading-ledger` | append-only account/order/fill/position/balance/PnL facts | 交易所事实与本地审计分源；区间 PnL；游标增量同步；可重建快照 | Completed |
| CAP-16 | 常驻 Worker、定时循环、Job、租约、Outbox、通知 | `operations` | PostgreSQL task/outbox/lease、ShedLock 等价租约、Actuator/metrics | 多 Pod 单次领取、僵尸租约恢复、优雅停机、下次运行时间可见 | Completed |
| CAP-17 | React 管理台所有页面和 EventSource | `web-ui` | 仅 `/api/v2`；按领域 API client；SSE resume | 无 `/api/v1`、无加载瀑布、loading/error/empty/partial、桌面/移动 QA | Completed |
| CAP-18 | Python 旧 PostgreSQL/SQLite 历史 | `migration` | 只读离线导入器、manifest、row count/hash/sample reconciliation | 源只读、目标空库、可重复失败、完整审计链、无静默丢表 | Completed |
| CAP-19 | Docker、GitHub Actions、DockerHub、Argo CD、K8S | `platform` | Java API、Python quant 独立 Deployment；PostgreSQL；NetworkPolicy | 镜像签名/扫描、Secret 引用、迁移 Job、Argo Synced/Healthy、回滚演练 | In Progress |

## 实施波次

1. 平台地基：CAP-01、02、03、15、16，先建立身份、配置、目录、永久事实仓库和可靠任务基础。
2. 分析闭环：CAP-04、05、06、07、08、09、10、11，贯通收集、AI、量化和真正多轮工作流。
3. 交易闭环：CAP-12、13、14、15，贯通建议、反思、风控、测试网执行和对账。
4. 产品切换：CAP-17、18、19，切换前端、导入历史、双跑核对、发布和回滚演练。

同一波次按纵向能力交付，不允许只建表不提供用例/API/测试，也不允许先接 UI 再用假数据占位。

## Definition of Done

- `backend/gradlew clean test bootJar`、Python Ruff/mypy/pytest、前端 lint/test/build 全部通过。
- Liquibase 在空 PostgreSQL 执行两次，第二次无待执行 changeset；真实 PostgreSQL 测试覆盖唯一约束、并发领取、幂等和账本查询。
- Java API、Worker、Scheduler、OMS 和 Exchange adapter 不导入或启动根目录 `finbot` Python 包。
- `web-ui/src` 不包含 `/api/v1`，生产部署不包含 Python Web/Worker 命令和 SQLite 数据库路径。
- 定时模式和即时模式各完成一轮真实闭环：收集、清理、压缩、分析、三家多轮辩论、Chair、量化/风险、建议、Sol 初审与反思、Gate/Bybit 模拟执行或可解释的安全拒绝。
- 历史导入 manifest 对每个源能力给出 `imported`、`transformed`、`archived` 或显式 `not_applicable`，不允许未知或静默遗漏。
- Gate TestNet / Bybit Demo 的账户、订单、成交、持仓、余额和 PnL 事实可按时间区间查询并能从 append-only 事实重建当前快照。
- GitHub Actions 构建发布成功，Argo CD `Synced/Healthy`，所有 Pod Ready，SSE 经过 Cloudflare 仍持续收到 heartbeat，至少三轮定时循环无重复交易和异常重启。
- 回滚演练能恢复冻结 Python 入口及迁移前只读数据库快照；Java 新库保留事故快照，不做自动反向写回。

## 变更纪律

- 每个 capability 完成后更新本矩阵状态和对应测试证据。
- 已发布 Liquibase changeset 不修改，只追加；本迁移未发布前也按此纪律执行。
- 任何 API、状态机或表结构变更必须先落强类型契约和失败行为测试。
- 外部 HTTP 不进入数据库事务；高基数明细分页/游标读取，批量事实用 `ON CONFLICT`，看板查询使用 DTO projection。
- 所有 secret 仅通过环境变量或 K8S Secret 引用；日志、SSE、API 和历史导入报告必须脱敏。
