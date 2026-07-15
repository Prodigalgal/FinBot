# ADR-016 分段研究专案与交易所环境硬隔离

## 状态

Accepted，2026-07-15。

## 决策

工作流图负责“段内如何执行”，`ResearchCase` 负责“多个段和多个工作流如何协作”。不把跨工作流编排伪装成一张超大 DAG。

- 一次 `ResearchCase` 固定从 `EVIDENCE` 开始，压缩完成后分裂为 `LIVE_RESEARCH` 与 `DEMO_AUTOTRADE`。
- `EvidenceSnapshot` 是不可变共享边界。子工作流通过 binding 表引用 artifact，不复制 `research_artifact`，也不能修改父段内容。
- 两个分支各自持有 `workflow_run_id`、工作流版本、检查点、AI 调用、市场 scope 和结果；API 与 UI 允许用户分别选择两套图，未指定模拟版本时才继承实盘版本。
- 交易所环境使用统一枚举 `LIVE/TESTNET/DEMO`。环境属于 venue binding 和运行上下文，不属于 `CanonicalProduct`。
- 所有市场事实和研究结果显式携带环境。Bybit Demo 与 Mainnet 即使价格相同，也只允许数值相同，不允许数据主键、artifact 或账户命名空间相同。
- OMS 只接受 `TESTNET/DEMO`。任何 `LIVE` 执行请求在 domain risk guard 和数据库约束两层拒绝。

## 取舍

- 单独建 `ResearchCase/ResearchSegment` 会增加少量表和查询，但换来清晰的恢复边界、UI 时间线和分支独立性。
- 不使用 `parent_run_id` 一列承载全部语义，因为共享证据不是某个交易分支的附属品，且未来一个专案可激活多个自定义研究分支。
- 不为每个交易所创建产品子类；端点与能力差异由 adapter/capability 描述，避免产品模型膨胀。
- `ExchangeCapabilityQuery` 是应用层稳定端口，静态 capability catalog 描述当前 adapter 的精确能力；新增交易所先实现端口和 capability，再启用产品映射。
- 模拟环境行情不可用时不偷用实盘行情冒充。若 Bybit 官方明确 Demo 公共行情等同 Mainnet，adapter 仍记录 `DEMO` 环境和官方 Demo 路由；Gate TestNet 则走独立端点。

## 一致性与恢复

- Evidence 完成与 artifact binding 在一个短数据库事务中提交。
- 分支启动通过 case + segment type 唯一约束和 workflow idempotency key 防重。
- 某分支失败只更新该 segment；case 可为 `PARTIAL`，另一分支继续运行。
- 完成段不会因下游重试重新执行；只有显式“重建证据”才创建新 case/snapshot。

## 回滚点

- 可停用双分支启动，只保留 `LIVE_RESEARCH`；新表和环境列保留，不删除历史。
- 不回退环境唯一键。回滚应用版本时必须继续禁止写入无环境行情。
