# 默认信源目录 v2

## 目标

v2 在保留 v1 的 11 个稳定来源 ID 的前提下，增加可免费访问的官方结构化来源，并把能用结构化协议表达的来源从 HTML/Firecrawl 迁移到 RSS 或 JSON API。目录共 16 项，默认启用 11 项；目录 manifest 只记录默认覆盖面和版本，不锁死用户在 UI 中的后续编辑。

## 默认策略

| 来源 | 协议/渠道 | 默认状态 | 凭据 | 出口策略 |
| --- | --- | --- | --- | --- |
| SEC EDGAR | RSS/Atom | 启用 | 无 Key；User-Agent 必须含项目和联系邮箱 | `WEB_CRAWL`，规避出口封锁并保持低频 |
| GDELT 全球新闻发现 | `SEARCH_DISCOVERY` | 启用 | 无 Key；遵守至少 5 秒请求间隔 | `WEB_CRAWL`，单来源低频轮询 |
| World Bank | JSON API | 启用 | 无 Key | `PUBLIC_DATA` |
| BLS | JSON API | 启用 | 无 Key（v1 公开接口） | `PUBLIC_DATA` |
| CFTC COT | JSON API/Socrata | 启用 | 无 Key | `PUBLIC_DATA` |
| FRED | JSON API | 默认停用 | UI 或环境变量写入免费 API Key | `PUBLIC_DATA` |
| EIA | JSON API | 默认停用 | UI 或环境变量写入免费 API Key | `PUBLIC_DATA` |

现有 Federal Reserve、ECB RSS、Gate 公告和其他来源继续保留。Bybit 公告切换为官方公开 JSON API，White House 切换为官方 RSS；OPEC 仍保留 HTML 页面作为正文补充。Firecrawl 仍是独立的显式渠道，不能由 first-party 失败自动触发。

## 凭据与热更新

FRED/EIA 使用通用 `FINBOT_INFORMATION_SOURCE_KEYS_JSON` 绑定，密钥只在请求构造时追加到上游查询参数，`raw_evidence`、`source_fetch_attempt`、日志和 API 响应中只保存脱敏 URL。UI 通过来源的 credential binding 显示“需要 API Key”，支持保存、清除、输入值本地显示/隐藏和测活；Key 与来源/字段绑定，不与模型或厂商名称绑定。

## 可靠性边界

- 免 Key 不代表无限频：GDELT 返回 `429` 时按 `Retry-After`/指数退避处理，来源轮询不能快于 5 秒。
- SEC 要求声明式 User-Agent；生产默认使用 `FinBot/2.0 (+https://github.com/Prodigalgal/FinBot; contact=finbot@omnnu.xyz)`，若出口仍被阻断，健康面板显示 `ACCESS_BLOCKED`，不会伪装成成功。
- CFTC 使用 `resource/gpe5-46if.json` 并显式按 `report_date_as_yyyy_mm_dd DESC` 排序，避免默认分页返回历史旧行。
- JSON 采集器只负责校验和分块，不强行解释业务字段；没有顶层标题时使用来源显示名，后续由 AI 清洗从结构化 block 中提取事实。
- 所有来源仍经过统一超时、响应上限、重定向、代理 fail-closed、全局/来源/主机并发和 `source_fetch_attempt` 观测。

## 验收

1. Liquibase v2 manifest 保留 v1 历史，目录记录为 append-only；控制面按最新 `created_at` 读取当前版本。
2. 新增来源在 PostgreSQL 集成测试中具备协议、出口、启停和凭据绑定断言。
3. World Bank/BLS/CFTC 在线返回 2xx；GDELT/SEC 的 429/403 属于可观测的上游限制，测试不把它们静默改成成功。
4. FRED/EIA 无 Key 时返回明确 `JSON_CREDENTIAL_MISSING`，填入 Key 后只影响请求，不污染持久化证据 URL。
