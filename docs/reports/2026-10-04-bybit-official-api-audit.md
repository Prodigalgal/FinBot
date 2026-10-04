# Bybit 官方接口与 CFD 接入核对

核对日期：2026-10-04。范围：官方接入文档、目录/行情/资金费协议、Demo 与已有 AI 子账户的只读验证。没有创建交易所订单、修改账户、签署协议或创建密钥。

## 产品身份与接口边界

| 指定商品 | 接入方式 | 当前结论 |
|---|---|---|
| BTCUSDT、ETHUSDT、DOGEUSDT | V5 `category=linear` | Bybit USDT 永续，使用公开 LIVE 行情进行本地模拟 |
| XAUUSDT、SKHYNIXUSDT、SNDKUSDT、MUUSDT、QQQUSDT | V5 `category=linear` | 分别核对精确 symbol、LinearPerpetual、USDT quote/settle、数量和价格限制；不能只按旧 product category 判断 |
| NAS100.s | MT5 CFD | 官方明确 CFD 不能通过 V5 查询或交易；保留精确产品映射，等待独立 CFD 行情接入 |

[官方 TradFi Integration](https://bybit-exchange.github.io/docs/v5/tradfi-integration) 明确区分 TradFi 永续和 MT5 CFD，并规定 `stock`、`ETF`、`commodity`、`forex` 是永续目录的 symbolType。V5 Key 不会使 NAS100.s 变成支持的 V5 产品。网页使用的 `/x-api/fapi/copymt5/*` 不属于这份公开 V5 合约。

## 密钥核对

在服务器管理 private 目录发现两组既有凭据：FinBot Demo Key、此前 OAuth 返回的 AI 子账户 V5 Key。2026-10-04 对各自的正确环境使用官方 `GET /v5/user/query-api` 和 `GET /v5/account/info`，均返回 HTTP 200、retCode=0。两组 Key 都是绑定 IP 的子账户 Key；其值与签名未写入本仓库或报告。

Demo Key 返回到期时间 `2026-10-12T07:30:24Z`，AI 子账户 Key 返回 `2026-12-29T09:48:31Z`。这证明当前 V5 认证可用，不证明存在 MT5 行情权限。Key 具有读写权限，本次所有外部账户验证只使用 GET；本地模拟无需加载 AI 子账户实盘凭据。

来源：[Key 信息](https://bybit-exchange.github.io/docs/v5/user/apikey-info)、[Demo 环境规则](https://bybit-exchange.github.io/docs/v5/demo)。Demo 的 REST 域名为 api-demo.bybit.com；Demo、Testnet、Mainnet Key 和域名不能混用，Demo 功能集合也不等于主网。

## 403 的证据及判断限度

之前本机和 FinBot 的交易所出口读取 CFD 网页公开路径均收到 HTML Access Denied/edgesuite 响应；登录浏览器能够读取。此次同一只读验证程序的 V5 认证接口成功，因此不能把 CFD 路径的拒绝归因于 Key 不正确，也不能据此断言整个 Bybit API 被阻断。

[接入指南](https://bybit-exchange.github.io/docs/v5/guide) 说明美国或中国大陆 IP 对 V5 有地域限制；[限频规则](https://bybit-exchange.github.io/docs/v5/rate-limit) 说明默认 HTTP 限制为每 IP 每 5 秒 600 请求，超过时可能返回 403 access too frequent。这些是官方列举的原因，不能直接用来解释另一个网页域名的 CDN 拒绝。当前请求无 GET body、频率很低，没有得到 access too frequent 或明确地域错误，确切的 CDN/WAF 匹配规则无法从响应确定。

处理方式：V5 永续继续使用官方 API；CFD 使用被支持的 MT5 只读行情桥接或 Bybit 正式提供的 CFD 数据服务。保留原商品，不替换成 QQQ、通用 Nasdaq 价格，不把用户浏览器会话复制到服务器，也不反复探测被拒绝的网页路径。

## 对本轮实现的修正与约束

- 盘口来自 `GET /v5/market/orderbook` 的第一档；有效时间使用 matching engine `cts`，与 `ts`、响应时间和本地观察时间做顺序检查。不能用 HTTP 响应的当前 time 把停止更新的旧盘口当成新报价；标记价快照另外检查时效。[Orderbook 协议](https://bybit-exchange.github.io/docs/v5/market/orderbook)、[Tickers](https://bybit-exchange.github.io/docs/v5/market/tickers)。
- 仅消费完整的已结束一分钟 K 线，逆序分页后按时序处理；USDT 合约 volume 单位是基础资产。缺分钟时停止推进；不能利用创建挂单前的高低价成交。[Kline](https://bybit-exchange.github.io/docs/v5/market/kline)。
- 资金费使用历史结算时点与有符号 rate；根据合约 fundingInterval 检查缺失，不用当前 fundingRate 代替过去费用。[Funding History](https://bybit-exchange.github.io/docs/v5/market/history-fund-rate)。
- 合约最大数量可能定期改变，metadata 不能永远缓存。新单和撮合核对当前 tick/step/min/max/settle/合约类型。[Instruments Info](https://bybit-exchange.github.io/docs/v5/market/instrument)。
- 股票拆分期间官方说明 status 仍可能为 Trading、暂停价格更新，并会调整历史 K 线。当前新报价时间校验可阻断停止更新的盘口；**拆分后仓位、限价、数量的公司行动调整尚未实现**，遇到拆分公告应暂停相关商品的新模拟单并单独核对已有仓位，不能把调整后的回补价格当作策略收益。[官方拆分说明所在接入指南](https://bybit-exchange.github.io/docs/v5/tradfi-integration)。

## 验证与后续

本地领域/应用/API/架构/行情解码测试、前端 38 项测试、构建与控制面契约检查通过。完整 PostgreSQL、迁移和发布状态见本轮发布验收报告；本报告不将本地构建等同于线上发布。

NAS100.s 自动行情、交易会话日历与隔夜 swap 的历史成本仍缺真实接入。CFD 开通凭据不能由现有 V5 Key 自动推导。后续接入只读行情桥接时需提供原始 bid/ask、报价时点、交易会话、lots/contract size、swap 时点与币种，继续使用独立模拟账本。
