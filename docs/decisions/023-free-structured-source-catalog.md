# ADR 023：免费结构化信源目录与协议路由

## 状态

Accepted，2026-07-18。v2 changeset 已写入源码，生产发布需经过 CI PostgreSQL 集成门禁和人工发布授权。

## 决策

1. 默认目录从 v1 追加 v2，不复用或覆盖 v1 manifest；`information_source_catalog_manifest` 使用 `(catalog_id, catalog_version)` 主键，控制面按 `created_at` 读取最新版本。
2. SEC EDGAR、GDELT、World Bank、BLS、CFTC 默认启用且不要求 API Key；FRED/EIA 使用同一个信息源 Key 绑定，缺 Key 时默认停用并返回明确错误。
3. 能使用官方 RSS/JSON 的来源优先走 `RssSourceCollector`/`JsonSourceCollector`；Bybit 公告使用官方 JSON，White House 使用官方 RSS，HTML 仅保留 OPEC 和需要正文补充的页面。
4. SEC/GDELT 使用 `WEB_CRAWL` 独立代理出口；World Bank/BLS/CFTC/Bybit 使用 `PUBLIC_DATA`。代理不可用时按来源策略 fail-closed，不回退直连。
5. CFTC 使用 Socrata `resource` API 并显式按报告日期降序，避免默认查询返回历史旧数据；JSON 采集器不猜测业务字段，没有顶层标题时使用来源显示名，事实解释交给后续 AI 清洗。
6. Firecrawl 与 first-party 是独立渠道。Firecrawl 默认关闭，并受来源级预算、连续失败熔断和独立健康状态约束；first-party 失败不得隐式触发 Firecrawl。

## 取舍与风险

- 免费服务仍有频控和出口风控：GDELT 当前出口可能返回 429，SEC 可能返回 403；系统记录真实状态，不将网络限制包装成成功。
- v2 对默认来源只在仍保持初始 first-party 配置时执行转换，尽量不覆盖管理员已经编辑的来源；用户修改后的来源由 UI 版本控制。
- 本地没有 Docker/PostgreSQL 服务，迁移 SQL 的真实执行由 CI PostgreSQL service 验证；在 CI 通过前不直接对生产数据库执行 changeset。

## 回滚

044 rollback 删除 v2 manifest、归档而不是物理删除新增来源（避免已有证据外键断裂），恢复受保护的 v1 source 配置，并恢复 manifest 单版本主键。对迁移后已被管理员再次修改的来源，rollback 条件不会强制覆盖其运行时配置。
