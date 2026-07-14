# ADR-002：P1 评估、组合风险与 AI 治理

## 状态

Accepted - 2026-07-10

## 决策

### 建议评估

- 评估对象继续使用 advisory-only `ai_trade_decisions`，不新建虚拟成交或账户事实。
- 使用持久化 candle 做 point-in-time 对齐；entry 数据必须在建议时已经存在，exit 数据必须在 horizon 到期后存在。
- outcome 使用幂等键 `decision_id + horizon`，聚合指标从 outcome 重算，不维护易漂移的累计计数器。

### 组合风险

- P1 风险引擎消费当轮产品建议和本地历史 candle，不调用私有交易 API。
- 相关性、集中度和压力情景均输出数据覆盖率与假设；数据不足不会被解释为低风险。
- 风险门禁是建议附加信息，不改变 `execution_allowed=false` 的全局策略。

### AI 治理

- FinBot 保存自己的 invocation、Prompt version、experiment 和 claim audit 契约，不依赖特定 Agent 框架。
- Prompt template version 与每次渲染后的 input hash 分开；前者用于版本比较，后者用于单次调用审计。
- A/B 分流使用稳定 hash，不使用进程内随机数；历史结果可按 variant 回溯。
- 成本估算只在站点费率已配置时生成，避免把未知费用误报为 0。

### 内置角色

- 内置角色是只读预设，经 API 返回时根据已有 enabled sites 动态绑定 model。
- 角色只保存 `site_id` 与 model，不保存 key；所有调用继续通过 `AISitesConfigStore` 和 keys file 解析凭据。

## 取舍

- 基于已有 candle 的 outcome 更可审计，但它不是撮合级回测；报告必须保留这一限制。
- 标准库实现相关性和风险统计可避免新增重型依赖，但 P1 不覆盖期权 Greeks、协方差收缩或蒙特卡洛 VaR。
- 原生实验分流与治理台账增加少量 schema 和配置复杂度，换取 provider/model/prompt 对比的可解释性。

## 兼容策略

- 所有数据库变更为 additive migration，不删除 P0 表或字段。
- 旧 AI 配置缺少角色预设、费率和 experiments 时加载安全默认值。
- 旧建议缺少 provenance 时归入 `legacy/unknown` 分组，不丢弃历史样本。

