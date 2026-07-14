# 信息源宇宙目录

这一份文档只解决一个问题：我们的第一层信息源要足够多、足够广，并且每个源都知道应该怎么接。

优先级原则：

```text
官方 API / 官方 RSS / 官方邮件订阅
  > 官方网页低频抓取
  > 权威新闻 API / RSS
  > Firecrawl Search + Scrape 补盲
  > 社交和论坛线索
```

如果有官方订阅，优先用订阅；没有订阅，就用网页抓取；网页结构复杂或需要动态发现，就用 Firecrawl；搜索结果只用于发现，不直接当事实。

## 接入模式

| 模式 | 适用源 | 优点 | 风险 |
| --- | --- | --- | --- |
| `structured_api` | FRED、BLS、BEA、EIA、SEC、交易所行情 | 稳定、结构化、易校验 | 需要 key、限流、字段学习 |
| `rss` | Fed、ECB、State、CME、新闻站 | 快、低成本、适合轮询 | 只给标题摘要，正文要二次抓 |
| `email_subscription` | OFAC 等已退 RSS 的源 | 官方推送、可靠 | 需要邮箱接入和解析 |
| `firecrawl_scrape` | 官方网页、公告页、IR 页面 | 可以转 Markdown 给 AI | 要限流、去重、尊重访问边界 |
| `firecrawl_search_then_scrape` | 突发事件、长尾新闻 | 覆盖广，适合补盲 | 噪音高，必须做可信度评分 |
| `provider_api` | OpenBB、Alpha Vantage、GDELT、Yahoo | 快速聚合 | 来源混杂，需要回溯原文 |
| `social_fetch` | Future direct social APIs | 早期线索 | 第一版不作为主路径 |

## 官方和一级源

### 宏观和利率

| 源 | 方式 | 价值 | 覆盖资产 |
| --- | --- | --- | --- |
| Federal Reserve | RSS + 网页抓取 | FOMC、讲话、监管、政策变化 | NAS100、XAUUSD、BTC、DXY |
| FRED | API | 利率、收益率、通胀预期、美元指数等宏观序列 | NAS100、XAUUSD、BTC、DXY |
| BLS | API | CPI、PPI、非农、失业率 | NAS100、XAUUSD、BTC、DXY |
| BEA | API | GDP、PCE、个人收入、贸易数据 | NAS100、XAUUSD、DXY |
| ECB | RSS + 网页抓取 | 欧元区利率、讲话、政策 | EURUSD、DXY、黄金、纳指 |
| BOE / BOJ / PBOC | 网页/RSS/API 视可用性 | 全球央行联动 | 外汇、黄金、指数 |

### 能源和商品

| 源 | 方式 | 价值 | 覆盖资产 |
| --- | --- | --- | --- |
| EIA | API + 周报页面抓取 | 原油库存、产量、进口、炼厂开工 | XTIUSD、USOIL、Brent |
| OPEC | 官网抓取/RSS 视可用性 | 产量政策、会议、声明 | 原油 |
| IEA | 官网抓取/API 视可用性 | 需求预测、能源报告 | 原油、天然气 |
| CME Group | RSS + 网页抓取 | 期货公告、市场结构、合约信息 | 原油、黄金、利率、外汇 |
| ICE | 官网抓取/订阅 | Brent、能源期货相关公告 | Brent、能源 |
| API Weekly Statistical Bulletin | 订阅/人工源 | 原油库存前瞻 | 原油 |

### 地缘和政策

| 源 | 方式 | 价值 | 覆盖资产 |
| --- | --- | --- | --- |
| White House | 网页抓取/订阅入口 | 总统公告、行政令、声明 | 原油、黄金、纳指、美元 |
| U.S. Department of State | RSS | 外交、制裁、地区冲突 | 原油、黄金、美元 |
| U.S. Defense / war.gov | RSS | 军事行动、国防声明 | 原油、黄金、纳指 |
| U.S. Treasury | 网页抓取 | 财政、债务、制裁相关声明 | 美元、黄金、纳指、原油 |
| OFAC | 邮件订阅 + Recent Actions 抓取 | 制裁名单、能源和金融制裁 | 原油、黄金、加密 |
| UN / NATO | 网页抓取/RSS 视可用性 | 战争、冲突、国际安全 | 原油、黄金 |
| GovInfo | RSS | 法规、官方文件、政策线索 | 全市场 |

### 公司和证券

| 源 | 方式 | 价值 | 覆盖资产 |
| --- | --- | --- | --- |
| SEC EDGAR | API | 10-K、10-Q、8-K、重大事项 | NAS100 成分股 |
| 公司 Investor Relations | Firecrawl 抓取 | 财报、公告、电话会 | NAS100、个股 |
| 公司 Newsroom | Firecrawl 抓取 | 产品、监管、事故、裁员 | NAS100、个股 |
| Nasdaq / NYSE 公告 | 网页抓取/API 视可用性 | 停牌、上市、规则 | 指数和个股 |

## 新闻和专业媒体

### 一线综合新闻

优先用 API/RSS；没有就使用 Firecrawl Search 限域搜索，再抓正文。

| 源 | 接入建议 | 说明 |
| --- | --- | --- |
| Reuters | search + scrape | 一线新闻，权重高 |
| AP | search + scrape | 一线新闻，权重高 |
| Bloomberg 公开页 | search + scrape | 公开页可用，付费内容不抓 |
| CNBC | search + scrape/RSS | 市场反应快 |
| MarketWatch | search + scrape/RSS | 传统市场新闻 |
| Yahoo Finance | provider API/yfinance | 股票和市场新闻聚合 |
| Investing | search + scrape | 多资产覆盖 |

### 宏观和外汇

| 源 | 接入建议 | 说明 |
| --- | --- | --- |
| ForexLive | search + scrape/RSS | 宏观交易员关注高 |
| DailyFX | search + scrape | 黄金、外汇、指数 |
| FXStreet | search + scrape | 黄金、外汇、宏观 |
| Kitco | search + scrape | 黄金和贵金属 |

### 能源和商品

| 源 | 接入建议 | 说明 |
| --- | --- | --- |
| OilPrice | search + scrape | 原油市场叙事 |
| Rigzone | search + scrape | 油气行业 |
| Offshore Energy | search + scrape | 航运、能源设施 |
| World Oil | search + scrape | 油气产业 |

### 加密货币

| 源 | 接入建议 | 说明 |
| --- | --- | --- |
| CoinDesk | search + scrape/RSS | 加密新闻权重高 |
| Cointelegraph | search + scrape/RSS | 快，但要去噪 |
| The Block 公开页 | search + scrape | 行业新闻 |
| Decrypt | search + scrape | 行业新闻 |
| Blockworks | search + scrape | 行业和机构视角 |
| Binance/Bybit/Gate 公告 | scrape/RSS 视可用性 | 交易所规则和上新 |

## 搜索和聚合源

| 源 | 方式 | 价值 |
| --- | --- | --- |
| Firecrawl Search | search | 动态发现长尾网页 |
| Firecrawl Scrape | scrape | 把网页转 Markdown 给 AI |
| GDELT DOC | API | 全球新闻发现和多语言覆盖 |
| Serper / Google Search API | API | 补充搜索覆盖 |
| Google News RSS | RSS | 新闻聚合发现 |
| OpenBB | provider API | 抽象多个金融数据和新闻 provider |
| Alpha Vantage NEWS_SENTIMENT | API | 新闻和情绪聚合 |
| Yahoo/yfinance | provider API | 股票新闻和行情 |

## 社交和传闻源

这些源只做线索，不做事实结论。

| 源 | 方式 | 价值 | 限制 |
| --- | --- | --- | --- |
| X / Twitter | API/合法可访问方式 | 突发传闻最快 | 噪音高，真假混杂 |
| StockTwits | Firecrawl scrape | 股票和加密情绪 | 交易者观点偏差 |
| Telegram/Discord | 授权频道 | 加密圈早期线索 | 需要权限和去噪 |
| Polymarket | API/抓取 | 事件概率变化 | 不是事实，只是市场预期 |

## 行情确认源

行情源不解释新闻，但用来判断“消息是否已经打到价格上”。

| 源 | 方式 | 数据 |
| --- | --- | --- |
| Bybit | Public REST/WebSocket | ticker、kline、orderbook、trades、funding、OI |
| Gate | Public REST/WebSocket | spot/futures/cfd 可用数据需逐项验证 |
| CCXT | library | 多交易所标准化 |
| CME/ICE 数据 | 订阅/公开延迟数据 | 期货市场确认 |
| Yahoo/Alpha Vantage/OpenBB | API | 传统市场辅助行情 |

## 抓取优先级

### P0：必须高频

- 已知日历事件：FOMC、CPI、非农、EIA 原油库存。
- 官方突发源：Fed、White House、State、Defense、Treasury/OFAC。
- 行情异常：原油、黄金、纳指、BTC 出现大幅波动和放量。

### P1：高价值补盲

- Reuters/AP/CNBC 等一线新闻。
- OilPrice、ForexLive、CoinDesk 等垂直媒体。
- Firecrawl Search 的事件关键词搜索。
- 交易所公告。

### P2：慢频覆盖

- 公司 IR/Newsroom。
- 行业媒体。
- GovInfo、政策文件。
- StockTwits 等社交情绪。

### P3：深度回填

- 历史文章。
- 事件后背景资料。
- 分析长文。
- AI 研究需要的补充证据。

## 官方源优先规则

同一个事件如果同时出现这些来源：

```text
社交爆料 -> 小媒体转载 -> Reuters 报道 -> 官方公告
```

系统要把事件链合并为一个 cluster，并把事实权重逐步提高：

```text
T5 social: clue only
T3 vertical media: possible event
T2 wire/news: likely event
T1 official: confirmed event
T0 market: price reaction confirmed
```

AI 分析层应该看到这个过程，而不是只看到最后几篇文章。

## Firecrawl 使用边界

Firecrawl 最适合：

- 没有 API/RSS 的官方公告页。
- 搜索发现的长尾新闻页。
- 公司 IR、Newsroom。
- 事件专题页。
- 页面转 Markdown 供 AI 阅读。

Firecrawl 不适合：

- 高频行情。
- 盘口和成交数据。
- 登录后页面。
- 付费内容。
- 无限深度爬取。
- 未经过滤的全网扫。

## 第一阶段推荐覆盖清单

第一版至少覆盖这些：

1. Market：Bybit、Gate、CCXT。
2. Macro official：Fed RSS、FRED、BLS、BEA、ECB RSS。
3. Energy official：EIA API、OPEC 页面、CME RSS。
4. Geopolitical official：White House、State RSS、Defense RSS、Treasury/OFAC。
5. News：Reuters、AP、CNBC、Yahoo Finance、ForexLive、OilPrice、CoinDesk。
6. Discovery：Firecrawl Search/Scrape、GDELT、OpenBB。
7. Social：StockTwits，X 后续按 API 可用性加入。

这套源的目标是：大事件从“官方、新闻、搜索、社交、行情”至少两个方向被捕获；真正重要的事件，应该能被三个以上方向互相验证。

## 参考入口

- Federal Reserve RSS: https://www.federalreserve.gov/feeds/feeds.htm
- EIA Open Data API: https://www.eia.gov/opendata/documentation.php
- SEC EDGAR APIs: https://www.sec.gov/search-filings/edgar-application-programming-interfaces
- FRED API: https://fred.stlouisfed.org/docs/api/fred/
- GDELT Data and APIs: https://www.gdeltproject.org/data.html
- CME RSS: https://www.cmegroup.com/rss.html
- OFAC RSS retirement note: https://ofac.treasury.gov/recent-actions/20241122
- ECB RSS: https://www.ecb.europa.eu/home/html/rss.en.html
- BLS API: https://www.bls.gov/bls/api_features.htm
- BEA API: https://apps.bea.gov/api/signup/
- State Department RSS: https://www.state.gov/rss-feeds
- Bybit public market API docs: https://bybit-exchange.github.io/docs/v5/market/kline
- Gate API v4 docs: https://www.gate.com/docs/developers/apiv4/en/
