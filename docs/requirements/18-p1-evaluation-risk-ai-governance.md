# P1 需求：建议评估、组合风险与 AI 治理

## 目标

- 为历史产品建议建立无前视偏差的结果追踪，输出命中率、方向收益、最大回撤、置信度校准和模型/实验变体对比。
- 对当轮方向性建议执行组合级相关性、产品/场所/资产集中度和确定性压力测试。
- 为 Council 建立 Prompt/model 版本、token/成本预算、确定性 A/B 分流和 claim 级证据覆盖率审计。
- 提供内置 Council 角色库；角色只绑定已有 AI site/model，密钥继续由现有站点配置和 keys file 解析。

## 范围

- `finbot/evaluation/`：建议到期、行情对齐、结果计算和聚合指标。
- `finbot/risk/`：组合权重、相关性、集中度、压力情景和风险门禁。
- `finbot/ai/governance.py`：调用台账、Prompt 版本、预算、实验分流和证据覆盖审计。
- `finbot/config/ai_sites.py`：内置角色预设、AI site 费率元数据和实验配置。
- `finbot/autonomous/`：P1 步骤编排和建议 provenance。
- `finbot/storage/sqlite_store.py`：P1 additive schema 与查询接口。
- `finbot/web/service.py`、`web-ui/`：P1 状态、报告和角色预设交互。
- `tests/`：评估、风险、治理、API 和兼容测试。

## 非目标

- 不把历史评估称为真实成交回测；不模拟滑点、手续费、撮合和账户资金曲线之外的成交事实。
- 不使用建议生成后的数据回填 entry price；缺少到期行情时必须保持 `pending` 或 `insufficient_data`。
- 不开放真实持仓、账户、下单、撤单、转账或杠杆调整接口。
- 不在仓库、API、角色预设、调用台账或日志中复制和回显 API key。
- 不把 AI claim 当作事实源；证据覆盖率只衡量引用完整性，不证明引用内容为真。

## 历史评估契约

- 每条 `ai_trade_decision` 对应至多一条指定 horizon 的 outcome；重复运行必须幂等。
- entry 优先使用建议发布时已保存的 `entry_reference`；缺失时只能使用不晚于建议时间的已存行情。
- exit、MFE、MAE、target/invalidation 只能使用 horizon 到期后已存的 candle；未到期不得提前评价。
- BUY/SELL 使用方向收益；HOLD/WATCH 单独统计中性命中，不混入方向收益命中率。
- 聚合报告至少包含样本数、pending/insufficient 数、命中率、平均/累计方向收益、最大回撤、Brier score、ECE、置信度分箱和 model/prompt/variant 对比。

## 组合风险契约

- 只把 BUY/SELL 视为方向性暴露；HOLD/WATCH 不伪造仓位。
- 权重来自建议中的 `max_position_notional_pct`，缺失时使用明确标记的等权假设。
- 相关性只使用时间戳对齐且样本数达到阈值的历史收益；数据不足输出 `insufficient_data`，不得填零冒充低相关。
- 集中度至少覆盖单产品、provider、canonical product/base asset 和相关性簇。
- 压力测试为明确标记的 hypothetical scenario，不得描述为预测。
- 风险报告只给 advisory gate 和风险警告，不允许触发交易执行。

## AI 治理契约

- 每次 Council role/chair 调用记录 task、role、site、protocol、model、Prompt template version、input hash、usage、耗时、状态和脱敏错误。
- site 可配置 input/output token 单价；未配置费率时成本为 `unknown`，不得按零成本汇总。
- token budget 是硬门禁；USD budget 仅在所需费率已配置时作为硬门禁，否则输出不可执行的配置警告。
- A/B variant 按稳定 hash 分流，同一 `experiment_id + loop_run_id + role_id` 重试保持同一 variant。
- claim audit 优先读取显式 `claims[].evidence_refs`，兼容旧消息时可从 assessment 级引用推导，并标注 `derived=true`。

## 内置角色契约

- 至少提供看多、看空、市场结构、风险、宏观、证据审计和组合风险角色预设。
- 预设按当前 enabled sites 解析 site/model/fallback；API 不包含 key。
- 用户从预设创建角色后得到普通可编辑角色副本，不与内置定义共享可变状态。

## 验收标准

- P1 三个步骤进入自动循环并持久化；旧 P8/P0 API 字段保持兼容。
- 已到期且行情充分的建议可生成确定性 outcome；未到期建议保持 pending。
- 风险报告能识别单产品集中、强相关簇和压力损失阈值。
- Council 调用可按 provider/model/prompt/variant 汇总 token、成本状态和证据覆盖率。
- Web 可使用内置角色预设，并展示最新评估、组合风险和 AI 治理摘要。
- `python -m compileall finbot`、`python -m unittest discover -s tests`、`npm run build` 通过。

## 回滚点

- `autonomous.run_recommendation_evaluation=false` 可停用评估步骤。
- `autonomous.run_portfolio_risk=false` 可停用组合风险步骤。
- `autonomous.run_ai_governance=false` 可停用治理汇总；调用台账仍保留已写入历史。
- AI experiments 默认空列表；清空后恢复固定 provider/model 路由。

