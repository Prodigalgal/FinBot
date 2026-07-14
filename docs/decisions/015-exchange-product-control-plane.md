# ADR-015 交易所与产品映射控制面

## 状态

Accepted

## 决策

产品与交易场所保持三层模型，不增加 CFD 专用产品表：

1. `canonical_product` 是与交易所无关的研究身份，承载资产对、展示名、类别和生命周期。
2. `venue_instrument` 是规范产品在某交易所的可交易映射，承载 symbol、market type、合约单位、价格/数量步长、最小数量、交易所最大杠杆和元数据状态。
3. `exchange_account` 是实际连接与执行边界，承载交易所、测试环境、凭据引用、代理路由和全局启用状态。

`venue_instrument.execution_enabled` 与 `status` 分离：`ACTIVE + execution_enabled=false` 表示可以采集行情和研究，但自动执行机器人不得选中。有效模拟执行必须同时满足：

- `canonical_product.status = ACTIVE`
- `venue_instrument.status = ACTIVE`
- `venue_instrument.execution_enabled = true`
- 至少一个同交易所 `exchange_account.enabled = true`
- 账户环境只能是 Gate `TESTNET` 或 Bybit `DEMO`

管理员通过带 `expectedVersion` 的账户开关启用或停用整个交易所连接。停用后，行情候选、账户同步、新风控候选和订单提交均停止使用该账户；产品和历史账本保留可见，不发生级联删除。

Watchlist 始终引用 `canonical_product`，可选 `preferred_instrument_id`。偏好映射不可用时，研究解析器只在已启用交易所的 ACTIVE 映射中按稳定排序选择；不会把偏好交易所复制进产品实体。

## 当前 TradFi 边界

Bybit Demo 可以查询 `AAPLUSDT` 等 TradFi Perpetual 的目录、行情、持仓和杠杆设置，但当前账户的开仓被协议/账户资格拒绝。因此这些映射保留为研究产品，并默认 `execution_enabled=false`。Gate TradFi、Bybit MT5 和其他 CFD 不在本阶段实现；只有官方 Demo API 完成开仓、成交查询、平仓和对账闭环后才可打开执行开关。

Binance USDⓈ-M Futures Demo 已发现 `TRADIFI_PERPETUAL` 目录，是下一候选 venue；因尚未取得 Demo key 完成私有交易闭环，本阶段只记录调查结果，不提前增加生产 `BINANCE` 枚举或账户。

## 扩展新交易所

新增交易所需要显式增加 `ExchangeVenue`、数据库约束、public market-data adapter、paper account adapter、签名测试和测试环境验收。开关现有交易所只修改账户配置，不修改产品或 Watchlist 数据。
