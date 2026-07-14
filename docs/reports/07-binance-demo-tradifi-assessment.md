# Binance Demo TradFi 可执行性调查

调查时间：2026-07-14（Asia/Shanghai）

## 结论

Binance USDⓈ-M Futures Demo 是当前最值得继续验证的“交易所式 TradFi 模拟执行”候选。官方 Demo 公共端点 `https://demo-fapi.binance.com/fapi/v1/exchangeInfo` 实时返回 `TRADIFI_PERPETUAL`，但在取得 Demo Futures API key 并完成最小开仓、成交查询、平仓和账户对账前，不得在 FinBot 中标记为可自动执行。

Binance Spot Test Network 和 COIN-M Futures Demo 可以补充加密模拟交易，不解决 CFD。Capital.com、IG、OANDA 提供更传统的 CFD Demo API，但属于经纪商连接，账户地区、品种和合规边界与交易所适配不同，应作为独立 venue adapter 立项。

## 实时目录证据

| 官方端点 | 返回规模 | 观察到的类型 |
| --- | ---: | --- |
| `testnet.binance.vision/api/v3/exchangeInfo` | 1,373 symbols | Spot 测试产品，包含明显测试资产，不能无筛选导入 |
| `demo-fapi.binance.com/fapi/v1/exchangeInfo` | 724 symbols | Perpetual、季度/周交割合约、`TRADIFI_PERPETUAL` |
| `demo-dapi.binance.com/dapi/v1/exchangeInfo` | 48 symbols | 币本位 Perpetual 与季度交割 |

USDⓈ-M Demo 共观察到 31 个 `TRADIFI_PERPETUAL` 条目。当前 `TRADING` 样本包括 `XAUUSDT`、`XAGUSDT`、`TSLAUSDT`、`METAUSDT`、`NVDAUSDT`、`SPYUSDT`、`QQQUSDT`；最小名义价值均为 5 USDT。目录同时包含 `PENDING_TRADING`、`SETTLING`、`FAKEKR000660USDT`、`OPENAIUSDT` 等沙箱或非标准条目，因此同步器必须验证状态、资产分类和允许清单，不能直接把整个 Demo 目录发布为真实产品库。

## FinBot 接入边界

接入 Binance 不改变 `canonical_product -> venue_instrument -> exchange_account` 模型：

- 新增强类型 `BINANCE` venue 和 `DEMO` account adapter。
- public adapter 读取 USDⓈ-M `exchangeInfo`、ticker、kline 和 funding rate。
- private adapter 实现 Futures Demo 的签名、杠杆、下单、撤单、订单、成交、持仓、余额和对账。
- 产品目录仅接收 `status=TRADING`，TradFi 资产还需显式分类规则；未知 symbol 进入隔离队列，不自动发布。
- 初始 `exchange_account.enabled=false`，所有 Binance instrument 初始 `execution_enabled=false`。
- 只有签名向量、最小单开平仓、幂等重复提交、断线恢复和永久账本对账全部通过后，才打开执行开关。

## 待验证

需要从 Binance Demo Futures 申请独立 API key/secret，禁止使用生产 key。验收顺序：

1. 私有账户与余额查询。
2. `XAUUSDT` 或 `TSLAUSDT` 最小合规订单。
3. 订单状态和成交明细查询。
4. 读取实际持仓数量并 `reduceOnly` 平仓。
5. 本地 OMS、fill、position、PnL 与交易所事实一致。

申请入口：<https://demo.binance.com/en/futures/BTCUSDT>

## 官方来源

- Binance Spot Test Network：<https://testnet.binance.vision/>
- Binance USDⓈ-M Futures General Info：<https://developers.binance.com/docs/derivatives/usds-margined-futures/general-info>
- Binance USDⓈ-M Demo Exchange Info：<https://demo-fapi.binance.com/fapi/v1/exchangeInfo>
- Binance COIN-M Demo Exchange Info：<https://demo-dapi.binance.com/dapi/v1/exchangeInfo>
