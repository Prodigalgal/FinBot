# 多领域国内外信源目录 v3

## 目标

FinBot 的信源覆盖不能只依赖单一综合新闻或单一抓取服务。v3 在 v2 的官方宏观、监管和交易所来源基础上，增加国内外综合新闻、科技、金融、农业、医疗、能源、安全、科研、气象灾害和更多交易所公告，并将 SearXNG 与模型原生 Web Search 作为独立的“搜索发现渠道”。目录负责发现和保留证据，事实裁决仍由后续多 Agent 清洗、压缩、验证和研究工作流完成。

默认目录由 Liquibase 046 以 append-only manifest 固化为 `v3`，包含 61 个稳定 source ID；Liquibase 047 在不修改历史变更集的前提下修正 SearXNG 引擎路由。manifest 只描述出厂目录；管理员仍可在 UI 中启停、修改或新增来源。

## 来源分层

| 分层 | 默认策略 | 代表来源 |
| --- | --- | --- |
| 官方结构化数据 | 优先启用、最高证据层级 | SEC、World Bank、BLS、CFTC、CISA、NVD、WHO、FDA、USDA、NOAA、USGS、NASA、Federal Register |
| 交易所一方信息 | 优先官方 JSON/RSS，缺少公开 API 时使用官方公告页 | Gate、Bybit、Binance、OKX、Coinbase、Bitget、KuCoin、Kraken |
| 国际综合新闻 | RSS 摘要直接采集，独立保留来源身份 | BBC、NPR、Al Jazeera、DW、Guardian、NYT、France 24、CBS、CNBC、MarketWatch、CoinDesk、SCMP |
| 国内综合与垂直新闻 | RSS 直接采集，按来源单独配置可信度和频率 | 中国新闻网、人民网、新浪、凤凰网、36Kr、IT之家、开源中国、少数派、cnBeta |
| 搜索发现 | 只生成带 canonical URL 的摘要证据，不伪装成原站正文 | GDELT、SearXNG 国际/新闻/国内主流/国内财经科技、Reuters/AP 定向发现 |
| AI Web Search | 独立调用已配置 AI Provider/Model，必须返回结构化 URL 引用 | Grok `web_search`、Gemini `google_search` |

FRED、EIA 需要免费 API Key，X 需要相应访问能力，Grok/Gemini Web Search 会产生模型费用，因此这些来源默认关闭。其他来源是否启用由迁移显式定义；上游拒绝、限流或代理无健康出口必须显示真实失败。

## 国内外综合新闻策略

1. 每个新闻站点是独立 `InformationSource`，不能把多个品牌聚合成一个不可追溯来源。
2. RSS/Atom 只保存标题、摘要、原文 URL、发布时间和来源元数据；后续需要正文时由显式 `HTML_DOCUMENT` 来源采集，不能把搜索摘要当正文。
3. 国内主流与财经科技补充通过 SearXNG 站点限定查询发现，当前覆盖新华社、央视、人民网、中国新闻网、澎湃、财联社、第一财经、证券时报、上海证券报、财新、36Kr 等站点。
4. 付费墙、登录墙、验证码和站点反爬不作为绕过目标。无正文时保留可验证摘要和 canonical URL，不生成虚构正文。
5. 新闻可信度由来源层级、独立交叉验证、时效和后续 AI 引用共同决定，不以搜索引擎排名替代事实验证。

## SearXNG 运行边界

- 生产只部署一个内部单副本 `finbot-searxng`，通过不同来源的 `engine_shortcuts`、`categories`、`language` 和查询模板选择国内、国际或新闻引擎。`engine_shortcuts` 是 FinBot 内部 endpoint 配置，Backend 必须将其编译为 SearXNG 支持的 `!shortcut` 查询前缀并从实际 HTTP query 中移除。
- 允许的引擎包括 360、Baidu、Bing、Brave、DuckDuckGo、Google、Qwant、Sogou、Startpage、Yahoo 及其新闻变体；配置必须与当前固定镜像的引擎注册表一致，镜像标记为 `inactive` 的引擎不得强制启用。
- `engines=` 不是当前 SearXNG Search API 的有效显式选引擎参数，Backend 必须将其视为配置错误，不能静默发送后伪装成已按引擎路由。快捷码必须满足小写字母/数字/下划线/连字符约束、去重且最多 16 个。
- SearXNG 不对公网暴露，NetworkPolicy 只允许 Backend 访问；所有上游请求必须经 `finbot-web-crawl-proxy`，代理无健康出口时 fail closed。
- `/healthz` 只证明进程存活。上线验收必须另外调用 `/search?...&format=json`，校验 HTTP 2xx、JSON `results` 数组以及至少一个合法公网 URL。
- SearXNG 只负责元搜索和结果规范化，不直接成为新闻事实来源；证据必须保留结果 URL、标题、摘要、引擎和抓取时间。

## AI Web Search 契约

`AI_WEB_SEARCH` 来源通过一对一 binding 绑定 `providerProfileId`、`modelName`、`reasoningEffort` 和 `tool`。Key 仍只与 Provider 绑定，不在来源或模型记录中复制。管理员修改 Provider/Model 后来源即时使用新运行时配置。

每次调用必须：

- 生成不可重复的 invocation ID，并只持久化 query hash、模型、token、状态和错误，不持久化 API Key。
- 要求上游返回非空答案及至少一个结构化 HTTP(S) URL 引用；缺少引用时整次失败。
- 拒绝带 userinfo、fragment、本机、私网和链路本地地址的引用。
- 将引用转换为 `CollectedPayload` 后进入与其他来源相同的不可变证据、AI 清洗和压缩验证链路。

Grok/Gemini 出厂来源默认关闭。管理员必须先完成 Provider 测活和模型探测，再按实际网关支持的工具协议启用；SearXNG 不作为 AI Web Search 的隐式 fallback，反之亦然。

## 验收标准

1. PostgreSQL 全新升级后最新 manifest 为 v3、source count 为 61、source ID 唯一且 hash 固定。
2. 国际综合新闻 12 项、国内直接新闻 9 项、国内 SearXNG 定向发现 2 项、交易所公告 8 项均有数据库断言。
3. `AI_WEB_SEARCH` 的领域校验、Provider/Model 外键、审计、引用缺失和私网 URL 拒绝都有测试。
4. Web 可筛选来源名称、类别、模式和启停状态，并可为 AI 来源选择已探测 Provider/Model、思考强度和工具。
5. Kustomize base/oracle 可渲染；生产 SearXNG 单副本 Ready，且真实搜索结果 smoke 通过。
6. Firecrawl 继续作为独立显式渠道且默认关闭，不参与任何自动 fallback。
7. 6 个 SearXNG 来源均使用 `engine_shortcuts`，数据库中不存在遗留 `engines` 配置；采集结果元数据保留 `search_engine_shortcuts`，证据 query 保留未注入 shortcut 的业务查询。
