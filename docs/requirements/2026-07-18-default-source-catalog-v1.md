# 默认信源目录 v1（历史版本）

> v1 是 2026-07-18 之前发布的历史目录。当前默认目录为 v2，见 [`2026-07-18-default-source-catalog-v2.md`](2026-07-18-default-source-catalog-v2.md)；v1 的 source ID 和 manifest 记录保持不变，用于审计与回滚。

## 目标

从本版本开始，FinBot 的默认信源集合固定为 11 个稳定入口。固定的是默认覆盖面、来源身份和资产映射，不是禁止管理员在 UI 中增加、停用或修改来源。用户自定义来源必须标记为 managed source，不得静默改变默认目录。

默认目录只负责“发现和采集证据”。行情、产品目录、交易所合约和模拟交易数据仍由交易所/产品模块负责，不在本目录重复维护。

## 固定目录

| source_id | 来源 | v1 角色 | 当前迁移方向 | 资产范围 |
| --- | --- | --- | --- | --- |
| `source_federal_reserve` | Federal Reserve | 官方宏观 RSS | `RSS_FEED` | NAS100、XAUUSD、BTCUSDT、DXY |
| `source_ecb_official` | ECB | 官方宏观 RSS | `RSS_FEED` | EURUSD、DXY、XAUUSD、NAS100 |
| `source_eia_weekly` | EIA Weekly Petroleum | 官方能源页面 | `HTML_DOCUMENT` | XTIUSD、USOIL、BZUSDT |
| `source_opec_news` | OPEC | 官方能源页面 | `HTML_DOCUMENT` | XTIUSD、USOIL、BZUSDT |
| `source_white_house` | White House Briefing Room | 官方地缘页面 | `HTML_DOCUMENT` | XTIUSD、XAUUSD、NAS100、BTCUSDT |
| `source_gate_announcements` | Gate Announcements | 交易所公告 | `HTML_DOCUMENT` | BTCUSDT、ETHUSDT、SOLUSDT |
| `source_bybit_announcements` | Bybit Announcements | 交易所公告 | `HTML_DOCUMENT` | BTCUSDT、ETHUSDT、SOLUSDT |
| `source_reuters_search` | Reuters | 一线新闻发现 | `SEARCH_DISCOVERY`（配置搜索端点后启用） | XTIUSD、XAUUSD、NAS100、BTCUSDT |
| `source_ap_search` | AP | 一线新闻发现 | `SEARCH_DISCOVERY`（配置搜索端点后启用） | XTIUSD、XAUUSD、NAS100、BTCUSDT |
| `source_x_market_search` | X 公开市场信息 | 社交线索发现 | `SEARCH_DISCOVERY`（配置搜索端点后启用，低信任） | BTCUSDT、ETHUSDT、SOLUSDT、XAUUSD、股票 CFD |
| `source_global_search` | 通用互联网搜索 | 长尾发现 | `SEARCH_DISCOVERY`（配置搜索端点后启用） | 加密、指数、黄金、原油 |

## 分层和默认策略

```text
T1 官方/交易所：Fed、ECB、EIA、OPEC、White House、Gate、Bybit
T2 一线新闻：Reuters、AP
T3 社交线索：X
T4 长尾发现：Global Search
```

- T1 默认启用，优先 RSS、JSON 或已知页面，不通过通用搜索发现。
- T2 使用搜索 Provider 先收集带 URL 的搜索摘要；后续正文抓取由 first-party crawler 独立调度，Firecrawl 仅作为单独配置的可选渠道。
- T3 只产生线索，不单独升级为事实；必须经过官方或一线新闻交叉验证。
- T4 只补充召回率，不能直接进入最终研究结论。

## 固定字段

每个默认来源必须拥有：

- 稳定 `source_id`、显示名、tier、category、trustWeight 和资产范围。
- 明确 `collectorProtocol`、发现策略、seed/feed/query 中至少一种入口。
- 明确 `OutboundRoute`、轮询周期、最大结果数和最大目标数。
- 明确是否允许 AI 清洗，以及是否启用 Firecrawl 渠道和对应操作。
- 采集结果必须进入统一 `raw_evidence`，再由工作流中的 AI 清洗、验证和压缩节点处理。

## 版本和变更规则

- v1 默认目录由 Liquibase seed、本文件和 `information_source_catalog_manifest` 共同定义；数据库 manifest 固化版本 `v1`、11 个 source ID 和 SHA-256 `d072d9c03dda10d7005a43906e50dbc0a4eda3d4df3b6bb40a18f868f9ed53c6`。seed 使用 `ON CONFLICT DO NOTHING`，不覆盖管理员运行时修改。
- manifest 表只追加新版本，不更新或删除既有版本；采集工作台和 OpenAPI 返回当前默认目录版本、条目数与哈希，便于发布验收和回滚核对。
- 增加默认来源必须更新目录版本、资产映射、采集协议、健康探测和测试 fixture。
- 删除默认来源先停用并观察至少一个轮询周期，不能直接删除历史证据。
- Firecrawl 是独立采集渠道；其 `FIRECRAWL_*` 操作模式只在管理员打开“显示 Firecrawl 渠道”后可配置，默认目录不启用。
- `SEARCH_DISCOVERY` 默认源在没有配置搜索 endpoint 时保持停用，避免把未配置的第三方搜索误报为可用。
