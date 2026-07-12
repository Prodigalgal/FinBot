# Goal：第一层采集能力全源接入

## 目标

完成 FinBot 第一层采集能力，让 `config/source_catalog.example.yml` 中列出的全部信息源进入统一采集系统。

更新边界：

- 邮箱订阅类信息源本阶段先不做真实邮箱接入。
- 需要 API key 的源，本阶段先完成代码、配置、调度和状态报告。
- 缺少 key 时不能阻碍系统启动、调度、smoke test 和其他源采集。
- 完成后输出需要申请的 key 清单。
- Firecrawl 默认使用已验证的 keyless 模式，不把 `FIRECRAWL_API_KEY` 作为必需 key。

这里的“全部接入”不是指所有源都无条件立刻能抓到数据，因为部分源需要 API Key、邮箱订阅、访问权限或后续确认 CFD 符号支持。工程上的“接入完毕”定义为：

```text
每个源都有配置；
每个源都能映射到一种采集适配器；
每个源都有调度策略；
每个源都有原始证据保存路径；
每个源都有健康状态；
每个源都有 smoke test 或明确阻塞原因；
采集结果可以统一转成 AI 研究输入包。
```

## 范围

本阶段覆盖这些源类型：

- 行情源：Bybit、Gate、CCXT 可扩展行情。
- 官方结构化 API：FRED、BLS、BEA、SEC EDGAR、EIA 后续结构化接口。
- 官方 RSS：Fed、ECB、State Department、Defense、CME、GovInfo 等。
- 官方网页：EIA、OPEC、White House、Treasury/OFAC、交易所公告、公司 IR。
- 新闻 Provider：Yahoo/yfinance、OpenBB、Alpha Vantage、GDELT。
- 新闻网页：Reuters、AP、CNBC、ForexLive、FXStreet、OilPrice、CoinDesk、Cointelegraph、Decrypt。
- 搜索补盲：Firecrawl Search + Scrape。
- 社交线索：StockTwits，X/Twitter 后续按 API 可用性加入。

## 不在本阶段范围

- 不做真实下单。
- 不接私有交易 API。
- 不保存交易所私钥。
- 不训练机器学习模型。
- 不绕过登录、验证码、付费墙。
- 不把 AI 结论直接变成交易动作。

## 接入状态定义

| 状态 | 含义 | 是否算入阶段验收 |
| --- | --- | --- |
| `configured` | 已在 source catalog 中声明，字段完整 | 是 |
| `adapter-ready` | 已有对应采集适配器类型 | 是 |
| `scheduled` | 已能由调度器生成任务 | 是 |
| `smoke-tested` | 已跑通过最小采集验证 | 是 |
| `blocked-by-credential` | 缺 API key 或授权 | 是，但必须有清晰阻塞说明 |
| `disabled-by-scope` | 本阶段明确不做，例如邮箱订阅接入 | 是，但必须不阻塞其他源 |
| `blocked-by-provider` | provider 不支持、接口变更或地区限制 | 是，但必须有替代方案 |
| `disabled-by-policy` | 付费墙、登录墙、禁止抓取或高风险源 | 是，但必须不被调度 |
| `deprecated` | 源已废弃或被替代 | 否，需迁移或移除 |

## 验收标准

### A. 配置验收

- `source_catalog.example.yml` 中全部源都能被配置加载器解析。
- 每个 source 都具备这些字段：
  - `id`
  - `enabled`
  - `tier`
  - `category`
  - `mode`
  - `trust_weight`
  - `poll_interval`
  - `priority`
  - `asset_scope`
- 每个 source 的 `mode` 能映射到一个 adapter。
- 每个 source 的 `asset_scope` 能映射到主题词表或资产注册表。

### B. 采集适配器验收

必须实现这些 adapter：

| Adapter | 覆盖源 |
| --- | --- |
| `exchange_public_api` | Bybit、Gate、CCXT 扩展 |
| `structured_api` | FRED、BLS、BEA、SEC、EIA |
| `rss` | 普通 RSS |
| `rss_then_firecrawl_scrape` | RSS 列表 + 正文抓取 |
| `firecrawl_scrape` | 固定网页抓取 |
| `firecrawl_search_then_scrape` | 搜索发现 + 正文抓取 |
| `provider_api` | yfinance、OpenBB、Alpha Vantage、GDELT discovery |
| `email_subscription_then_firecrawl_scrape` | OFAC 等邮件订阅源 |
| `social_fetch` | Future direct social APIs when explicitly enabled |

### C. 调度验收

- 调度器能按 `poll_interval` 生成任务。
- P0 源优先于 P1/P2/P3。
- 同域名有并发限制。
- Firecrawl 有 search/scrape 预算限制。
- 失败任务有 backoff。
- 同一 URL 不会在短时间内重复抓取。
- 支持手动触发某个 source 或某个 topic。

### D. 存储验收

必须保存：

- 原始请求。
- 原始响应。
- 响应 headers。
- 抽取后的正文 Markdown。
- source metadata。
- canonical URL。
- content hash。
- 抓取时间。
- provider 状态。
- 错误信息。

第一版使用 SQLite 即可，正文和原始证据可以落到本地 evidence 文件目录，数据库保存索引。

### E. 去重验收

必须支持：

- URL 规范化去重。
- canonical URL 去重。
- 标题归一化去重。
- 正文 hash 去重。
- 同一事件窗口内的相似标题合并。

### F. AI 输入包验收

采集层必须输出统一研究输入包：

```json
{
  "asset_scope": ["XTIUSD", "XAUUSD", "NAS100"],
  "time_window": "last_6h",
  "market_context": {},
  "event_candidates": [],
  "raw_document_refs": [],
  "source_health": {}
}
```

AI 层收到的每条信息必须包含：

- 来源。
- 可信度权重。
- 发布时间或抓取时间。
- 原文 URL。
- 原始证据 ID。
- 关联资产。
- 是否官方源。
- 是否行情确认。

## 33 个当前源的接入目标

| Source ID | Mode | 阶段目标 |
| --- | --- | --- |
| `market_bybit_public` | `exchange_public_api` | smoke-tested |
| `market_gate_public` | `exchange_public_api` | smoke-tested |
| `official_eia_weekly_petroleum` | `firecrawl_scrape` | smoke-tested |
| `official_opec_news` | `firecrawl_scrape` | smoke-tested |
| `official_federal_reserve` | `rss_then_firecrawl_scrape` | smoke-tested |
| `official_fred_api` | `structured_api` | blocked-by-credential or smoke-tested |
| `official_bls_api` | `structured_api` | smoke-tested if keyless endpoint works |
| `official_bea_api` | `structured_api` | blocked-by-credential or smoke-tested |
| `official_ecb_rss` | `rss_then_firecrawl_scrape` | smoke-tested |
| `official_state_department_rss` | `rss_then_firecrawl_scrape` | smoke-tested |
| `official_defense_rss` | `firecrawl_scrape` | smoke-tested via official releases page fallback |
| `official_sec_edgar_company_filings` | `structured_api` | configured, disabled until CIK list exists |
| `official_white_house` | `firecrawl_scrape` | smoke-tested |
| `official_us_treasury_sanctions` | `email_subscription_then_firecrawl_scrape` | disabled-by-scope for email, scrape fallback smoke-tested |
| `official_cme_rss` | `firecrawl_scrape` | smoke-tested via CME RSS landing page until exact feed URLs are selected |
| `official_govinfo_rss` | `rss_then_firecrawl_scrape` | smoke-tested |
| `news_yahoo_finance` | `provider_api` | smoke-tested |
| `news_openbb_world` | `provider_api` | adapter-ready |
| `news_alpha_vantage_sentiment` | `provider_api` | blocked-by-credential |
| `search_gdelt_doc` | `provider_api` + `firecrawl_scrape` follow-up | smoke-tested |
| `news_reuters_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_ap_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_cnbc_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_forexlive_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_fxstreet_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_oilprice_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_coindesk_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_cointelegraph_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `news_decrypt_search` | `firecrawl_search_then_scrape` | smoke-tested |
| `exchange_announcements_bybit` | `firecrawl_scrape` | smoke-tested |
| `exchange_announcements_gate` | `firecrawl_scrape` | smoke-tested |
| `search_firecrawl_global` | `firecrawl_search` | smoke-tested |
| `social_stocktwits` | `firecrawl_scrape` | smoke-tested if public symbol pages are crawlable |

## 完成定义

本 Goal 完成时，仓库里应至少具备：

1. 可运行的 Python 采集项目骨架。
2. 可解析的 source catalog 和 topic watchlists。
3. 统一采集任务模型。
4. 全部 adapter 类型的代码入口。
5. SQLite 存储和 evidence 文件目录。
6. Firecrawl search/scrape 客户端。
7. RSS/API/Provider/Market/Social 采集器。
8. 去重和标准化模块。
9. source health 记录。
10. 一键 smoke test，输出每个源的接入状态报告。

## 第一阶段交付顺序

1. 项目骨架和配置加载。
2. SQLite schema 和 evidence store。
3. RSS + Firecrawl scrape。
4. Firecrawl search + scrape。
5. Bybit/Gate public market。
6. GDELT/yfinance provider。
7. FRED/BLS/BEA/SEC 结构化 API。
8. 社交源。
9. 全源 smoke report。

## 风险

- 源太多，第一版必须靠统一 adapter 抽象，不能每个源单独写死。
- Firecrawl 不能无限抓，必须有预算、去重和域名限流。
- 新闻搜索源噪音大，必须依赖 source trust 和 event cluster。
- API key 缺失的源不能阻塞整体系统，但必须显示阻塞状态。
- 某些网页结构变化很快，所以 raw evidence 保存比只保存解析字段更重要。
