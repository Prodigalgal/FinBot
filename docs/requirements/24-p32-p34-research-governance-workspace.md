# P3.2-P3.4 研究治理工作台

## 目标

- 把产品研究结果收敛为可审计、可复核、可重放、可比较的运行历史，而不是只保留“最新报告”。
- 在 AI 决策和模拟执行之间建立强制人工复核状态机。
- 为 Council Workflow 增加草稿、发布版本、回滚、节点测试和运行前成本预估。
- 用 Shadow Portfolio、point-in-time 回测、置信度校准和站内通知完成建议发布后的反馈闭环。

## 范围

- P3.2：产品一键研究、研究/建议历史、运行详情、双版本对比、完整重放、失败续跑、人工复核收件箱。
- P3.3：Workflow draft/published/archived 版本、乐观锁、发布前校验、回滚生成新版本、节点测试和成本估算。
- P3.4：基于已批准建议的 Shadow Portfolio、基于历史 candle/outcome 的 point-in-time 回测与校准、确定性反思规则、站内通知策略。
- P3.1B：只有人工批准且所有门禁通过的 BUY/SELL candidate 才能进入 Gate TestNet / Bybit Demo。
- 技术型候选可通过跨交易所行情互证形成独立的 `market-confirmed`：至少 2 家不同 provider、方向一致、最低信号强度 0.60、最新价偏差不超过 1%。即使存在未完成的事件研究，也必须保留 `fundamental_research_status`、matched items 和风险标记，不得将技术确认表述为基本面确认。

## 非目标

- 不开放 Mainnet 私有 API、真实订单、资金划转、杠杆修改或任意交易所 host。
- 不展示隐藏 chain-of-thought；辩论历史只展示模型明确返回的结构化论据、质询、修订、分歧和证据引用。
- 不把建议历史评估称为撮合级回测，不用未来行情回填 entry，不在样本不足时输出伪命中率或伪校准指标。
- 不引入任意脚本节点、循环 DAG 或外部分布式工作流引擎。

## 人工复核状态机

- 决策首次出现时逻辑状态为 `pending`，不要求预先写入 review 行。
- 用户可将其更新为 `approved`、`rejected` 或 `changes_requested`；每次更新递增 `version`，过期版本写入返回 409。
- `approved` 前必须同时满足：决策状态 `candidate`、方向为 BUY/SELL、`decision_readiness.simulation_eligible=true`、行情/研究门禁通过、该轮 Portfolio Risk 与 AI Governance 通过。
- `needs-followup`、`manual-review`、`blocked` 不属于基本面研究确认；仅凭这些状态不得进入方向建议。独立的 `market-confirmed` 必须保留 provider、价格偏差、方向和阈值审计数据，并明确 `confirmation_scope=market-only`。
- 模拟执行必须再次读取当前 review；只信任客户端传入的 `decision_id`，不接受客户端覆盖 action、价格、数量、host 或 adapter 凭据。
- `paper_execution.require_human_review=true` 是默认且生产安全配置；关闭仅用于隔离单元测试，不作为 Web 可见快捷开关。

## 历史、重放与续跑

- 历史列表以 `autonomous_loop_runs` 为根，聚合 research pipeline、debate、decisions、review、paper execution、evaluation、risk 和 governance 摘要。
- 对比只比较结构化字段：运行状态、配置摘要、步骤耗时、候选/建议数量、决策就绪度、产品 action/confidence、成本和评估指标。
- replay 创建新的 autonomous request，并保存 `source_loop_run_id`；旧运行保持不可变。
- resume 仅用于存在失败 research pipeline 的运行，从失败步骤开始沿用既有 pipeline resume 契约；成功步骤不得重复制造派生产物。

## Workflow 版本契约

- `workflow_versions` 保存完整 Council template 快照和 checksum；`draft` 可修改，`published` 不可原地修改。
- 每个 template 最多一个 published 版本；发布在单事务内归档旧版本，并将已校验模板同步到 `AISitesConfigStore`。
- rollback 不篡改旧记录，而是从目标版本复制出新的 draft/published 版本。
- 节点测试只运行选定节点的 provider/prompt 合成与 schema 校验，禁止交易工具；测试输入和输出均脱敏落库。
- 成本预估根据节点、phase、round、最大输入/输出 token 和站点费率计算；任一费率未知时总成本状态为 `partial` 或 `unknown`，不得按 0 美元展示。

## Shadow Portfolio 与通知

- 只消费已批准的方向性建议；仓位数量由固定虚拟名义价值和 entry_reference 确定，不消费真实账户余额。
- mark/exit 只使用对应时间点已持久化行情；无行情时保持 `insufficient_data`。
- 输出持仓数、已实现/未实现收益、权益、峰值、最大回撤、样本量和数据覆盖率。
- 反思只生成确定性改进建议，例如样本不足、特定置信度分箱失准或证据覆盖不足；不得自动修改 Prompt 或发布 Workflow。
- 首版通知为本地站内 Inbox，按 dedupe key 幂等生成；支持 unread/read/dismissed，不对外发送消息。

## 验收标准

- 产品中心能将规范化产品、首选场所合约和 Watchlist 上下文带入即时研究。
- 历史、详情、对比、replay、resume 和人工复核均有 API、持久化、错误态和 UI 入口。
- 未批准决策即使 `submit_orders=true` 也不能提交模拟订单；批准后仍需通过所有执行层门禁。
- Workflow draft/publish/version/rollback/node test/cost estimate 可重复验证，旧配置继续兼容。
- Shadow Portfolio、回测/校准和通知对空样本、未到期样本和数据缺失给出明确状态。
- 全量 Python 测试、前端 build、HTTP smoke、桌面/移动浏览器 smoke 和常驻任务验收通过。
- K8S 运行时把代码资产与可变状态分离；SQLite 阶段使用单 Pod 双容器和共享 PVC，`replicas=1`、`Recreate` 为硬约束。

## 回滚

- 新表均为 additive schema；旧运行数据不迁移、不删除。
- `paper_execution.submit_orders=false` 可立即停止所有模拟写入；`paper_execution.enabled=false` 可移除自动循环中的执行计划。
- Workflow 发布失败不修改当前 `AISitesConfigStore`；恢复已发布版本可生成新 rollback 版本。
- Shadow/通知模块可从导航隐藏，不影响研究、Council、建议或既有评估链。
