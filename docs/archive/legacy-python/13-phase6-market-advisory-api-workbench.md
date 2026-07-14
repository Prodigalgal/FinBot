# Phase 6 设计：Market Data / Advisory API / Operator Workbench

## 1. 目标修正

P1-P5 的边界是 research-only，因此 P3/P4/P4.1 不输出 `BUY/SELL/target price/position`，也不接交易 API。

P6 开始目标调整为：

```text
允许接入真实交易所公共行情数据，允许生成交易建议；
禁止 AI 或系统调用任何真实下单、撤单、改仓、转账、杠杆调整等私有交易 API。
```

P6 把两个概念显式拆开：

- `data_source=live_public`：交易建议使用实盘公共行情，不使用 testnet/sandbox 行情作为市场判断依据。
- `execution_mode=advisory_only`：系统只生成建议和风险参数，不执行订单。未来即使加入 paper/testnet 演练，也只能作为执行沙盒，不能改变行情来源。
- 默认 symbol 集合保持很小，目前默认只跑 `BTCUSDT`。信源映射到产品之后，由 watch list 显式传入少量 symbols，不做全市场扫描。

这不是否定前面 research-only 的设计，而是把系统分成两层：

- P1-P5：证据、研究、验证、review council，保持中立，不产生交易动作。
- P6：在经过市场数据和研究上下文之后，生成 advisory-only 交易建议。

## 2. 当前实现

新增能力：

- Binance public market data
- Bybit public market data
- Gate public market data
- 统一 `MarketQuote`
- 统一 `MarketCandle`
- Advisory engine
- 多周期指标：默认 `1h / 4h / 1d`
- P4.1 research council context 注入
- 本地 paper order proposal ledger
- provider-aware 本地限流与 rate-limit evidence
- public REST 瞬时连接错误重试与失败 observation
- ProxyRuntime / ProxyRouter：Firecrawl 与 exchange 分 route 管理，默认禁止 silent direct
- sing-box bridge：把 CF Workers VLESS subscription 转成本地 HTTP proxy 候选
- provider-level proxy policy 热加载：支持 `config/proxy_policy.json` 与 `EXCHANGE_<PROVIDER>_ALLOW_DIRECT`
- Operator Workbench report
- 标准 Web API 入口由 P7 FastAPI 服务承载，旧的标准库 HTTP Operator API 已删除

核心文件：

```text
finbot/market/public_exchanges.py
finbot/advisory/engine.py
finbot/network/proxy_policy.py
finbot/network/proxy_router.py
finbot/network/proxy_runtime.py
finbot/network/sing_box_bridge.py
finbot/operator/paper_ledger.py
finbot/operator/workbench.py
finbot/cli/build_operator_workbench.py
finbot/cli/proxy_diagnostics.py
finbot/cli/serve_web.py
```

## 3. 交易所 API 边界

当前只调用公共行情端点：

| Provider | Quote | Candle | Private key |
| --- | --- | --- | --- |
| Binance | `/api/v3/ticker/24hr` | `/api/v3/klines` | 不使用 |
| Bybit | `/v5/market/tickers` | `/v5/market/kline` | 不使用 |
| Gate | `/api/v4/spot/tickers` | `/api/v4/spot/candlesticks` | 不使用 |

官方依据：

- Binance Market Data Only 文档说明 `data-api.binance.vision` 不需要 API key，只提供 public market data，并列出 `/api/v3/klines` 与 `/api/v3/ticker/24hr`。
- Bybit V5 Market 文档将 `/v5/market/tickers` 定义为最新价格、best bid/ask 和 24h volume snapshot，将 `/v5/market/kline` 定义为 historical klines。
- Gate API v4 Spot 文档列出 `/spot/tickers` 与 `/spot/candlesticks` 为市场数据端点，并把 `/spot/orders`、`/spot/price_orders` 等下单/撤单端点分列为私有交易操作。

禁止接入：

```text
POST /order
POST /orders
DELETE /order
DELETE /orders
position mutation
margin/leverage mutation
wallet transfer / withdraw
API key trading permission
```

## 4. Advisory Contract

P6 advisory 允许输出：

```json
{
  "action": "BUY | SELL | HOLD",
  "confidence": 0.62,
  "levels": {
    "entry_reference": 100.0,
    "target_price": 104.0,
    "invalidation_price": 98.0,
    "risk_distance_pct": 2.0
  },
  "position_sizing": {
    "risk_per_trade_pct": 0.5,
    "max_position_notional_pct": 5.0
  },
  "policy": {
    "market_data_source": "live_public",
    "execution_mode": "advisory_only",
    "execution_allowed": false,
    "exchange_private_api_allowed": false,
    "order_api_allowed": false,
    "human_confirmation_required": true
  }
}
```

也就是说：建议可以有方向、目标位、失效位和仓位风险参数，但系统不能执行。

## 4.1 抓取频率与代理池

公共行情不需要 key，但仍需要按 provider 做本地节流：

| Provider | 官方限制要点 | FinBot 本地保守预算 |
| --- | --- | --- |
| Binance | REST 按 IP weight；`/api/v3/klines` 和单 symbol `/api/v3/ticker/24hr` weight=2，超限可能 429/418 | 60 requests/min 且 120 local weight/min |
| Bybit | HTTP IP 限制 600 requests / 5s / IP；触发频繁访问或地区策略时可能失败 | 12 requests / 5s，418/429 后 10 分钟冷却；地区封禁类 403 记录为 provider block |
| Gate | public endpoint 约 200 requests / 10s / endpoint / IP | 30 requests / 10s |

代理池 smoke：

```text
proxy_pool_size=1
proxy_used=socks5://<redacted>@168.138.40.52:10800
server proxy process -> python3 proxy_pool.py listening on 0.0.0.0:10800
server IPv6 pool prefix -> 2001:470:36:3ea::/64
server ip_nonlocal_bind -> 1
server local route -> local 2001:470:36:3ea::/64 dev lo
remote-DNS SOCKS5 to api64.ipify.org -> HTTP 200, random 2001:470:36:3ea:* egress
remote-DNS SOCKS5 to Binance/Bybit/Gate domains -> SOCKS5 failure, domains expose IPv4 A records only in this environment
local-DNS SOCKS5 to Binance/Gate from Windows -> HTTP 200 via 168.138.40.52 IPv4 egress
local-DNS SOCKS5 to Bybit from Windows -> connect timeout
server local-DNS SOCKS5 to Binance/Bybit/Gate -> HTTP 200 via proxy process
```

结论：

- 这台代理机器和 `proxy_pool.py` 本身可用，且在目标域名有 IPv6 地址时可以从 `2001:470:36:3ea::/64` 随机出站。
- `proxy_pool.py` 对域名目标会强制 `AF_INET6`；如果目标域名只有 A 记录、没有 AAAA，`socks5h` / remote DNS 会失败。
- Binance、Bybit、Gate public REST 域名在本次服务器 DNS 结果中只返回 IPv4 A 记录，因此不能通过这条代理池强制 IPv6 抓取。
- 普通 `socks5://` 会由客户端先解析域名，再把 IPv4 目标交给代理；这仍然满足“请求必须经过代理池”的策略，但出口是 `168.138.40.52` IPv4，不是随机 IPv6。
- Firecrawl API `api.firecrawl.dev` 当前也只返回 IPv4 A 记录；Firecrawl adapter 应继续使用普通 `socks5://` 经过代理池，不应把该候选改成 `socks5h://`。
- P6 真实行情获取不依赖 Firecrawl route；exchange route 默认要求 IPv4 或 dualstack-capable proxy，不能 silent direct。
- CF Workers VLESS subscription 可以作为可选 IPv4 出口池，但 Python HTTP client 不能直接使用 `vless://`。当前实现通过官方 sing-box 生成本地 `http://127.0.0.1:<dynamic>` 代理，再交给 `httpx` / `curl`。
- Bybit 对部分 CloudFront/CF 出口存在地区阻断。默认情况下，workbench 会将其标记为 `provider-geo-blocked`；只有用户显式允许 `exchange:bybit.allow_direct=true` 时，才会在代理返回 provider block 后 fallback 到 direct，并把两次请求都写入 `rate_limit.observations.proxy_route`。

热更配置：

```powershell
# 环境变量方式，下一次 CLI/API build 生效
$env:EXCHANGE_BYBIT_ALLOW_DIRECT='1'

# 文件方式，复制 config/proxy_policy.example.json 到 config/proxy_policy.json 后修改：
# {
#   "routes": {
#     "exchange:bybit": { "allow_direct": true }
#   }
# }
```

说明：

- `config/proxy_policy.json` 是本地热策略文件，已加入 `.gitignore`；示例文件 `config/proxy_policy.example.json` 可提交。
- `ProxyRuntime.from_settings()` 每次 CLI build / API build 都重新读取该文件和环境变量，因此无需改代码；长驻进程里下一次 `/api/v1/operator/workbench/build` 会读取新策略。
- Direct fallback 永远不是全局默认，也不会静默发生；报告中的 `proxy_route.proxy=direct` 与 `policy.allow_direct=true` 是审计依据。

## 5. 数据模型

新增 SQLite 表：

```text
market_quotes
market_candles
advisory_reports
paper_order_proposals
```

用途：

- `market_quotes`：保存每次 public ticker snapshot。
- `market_candles`：按 provider / market_type / symbol / interval / open_time 去重保存 K 线。
- `advisory_reports`：保存 workbench 汇总和每个 provider-symbol 的建议。
- `paper_order_proposals`：保存本地 paper-only 计划，不连接任何交易所订单 API。

## 6. CLI

生成 Operator Workbench：

```powershell
python -m finbot.cli.build_operator_workbench --symbol BTCUSDT --provider gate --candle-limit 5
```

默认输出：

```text
data/reports/operator-workbench-latest.json
```

启动 Web API：

```powershell
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
```

Endpoints：

```text
GET /health
GET /api/v1/status
GET /api/v1/autonomous/status
GET /api/v1/operator/workbench/latest
POST /api/v1/jobs/operator-workbench
```

代理诊断：

```powershell
python -m finbot.cli.proxy_diagnostics --no-start-bridges
python -m finbot.cli.proxy_diagnostics --smoke
```

## 7. 验证结果

已验证：

```powershell
python -m compileall finbot
python -m unittest discover -s tests
python -m finbot.cli.proxy_diagnostics --no-start-bridges
python -m finbot.cli.proxy_diagnostics --smoke
python -m finbot.cli.build_operator_workbench --symbol BTCUSDT --provider gate --interval 1h --interval 4h --interval 1d --candle-limit 5 --timeout-seconds 20
python -m finbot.cli.serve_web --port 8780 --frontend-dist web-ui/dist
python -m finbot.cli.status
```

真实行情 smoke：

```text
Report: Gate-only workbench report
Status: ok
Summary: {"advice_count":1,"failed_count":0,"data_source":"live_public","execution_mode":"advisory_only","execution_allowed":false}
Providers: gate:ok
```

历史多 provider smoke 证明 Binance / Bybit / Gate 均可作为可选公共行情 provider；当前默认配置不再同时启用三家。

默认不允许 Bybit direct 的 CF VLESS smoke：

```text
Report: 3121e8e409c423f207e07cd5e435bb0137ac91b94577f737203f296608515750
Status: partial
Summary: {"advice_count":2,"failed_count":1,"paper_proposal_count":0,"actions":{"HOLD":2},"data_source":"live_public","execution_mode":"advisory_only","execution_allowed":false}
Bybit: provider-geo-blocked
```

Web API smoke：

```text
GET /health -> ok
GET /api/v1/operator/workbench/latest -> status=ok, advice_count=1, execution_allowed=false
```

## 8. 后续 P6.1

建议下一步：

- 补充真正可用的 IPv6 代理池候选，并重新验证 Bybit public REST。
- 补充更多非 Cloudflare 单一出口的 IPv4/dualstack 候选，降低 Bybit provider geo block 风险。
- 增加 Web Operator Workbench UI。
- 将 watch list 的产品映射直接接入 P6 symbols 参数，保持少量 symbols。
