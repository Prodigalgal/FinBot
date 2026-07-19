# ADR 026：可复用的爬虫请求头 Profile

## 状态

Accepted，2026-07-19。

## 背景

不同信息源可能需要不同的内容协商语言或站点约定请求头，但请求头不应散落在各个 Collector、来源 URL 或部署环境变量中。把请求头直接交给来源配置也会造成重复、无法统一校验，并且容易把凭据、转发头或伪造客户端身份发送到上游。

## 决策

1. 新增独立的 `CrawlerHeaderProfile` 聚合。Profile 只描述透明的爬虫身份和内容协商：`User-Agent`、可选 `Accept`、可选 `Accept-Language` 以及受限的附加头；不绑定厂商、模型、代理或交易所。
2. `InformationSource` 只保存 `crawlerHeaderProfileId`。创建和更新来源时必须绑定存在且启用的 Profile；数据库通过外键和事务内 `FOR KEY SHARE` 保护绑定与归档/停用之间的并发边界。
3. `CrawlerRequestHeaderPolicy` 是所有 first-party HTTP 请求的唯一入口。它在每次请求解析当前 Profile，因此后台更新下一次请求立即生效，不依赖重启或刷新部署。
4. Profile 的 `User-Agent` 必须明确识别 `FinBot`，并包含可验证的联系信息（`contact:` 或项目 URL）。不支持随机浏览器指纹、不伪造 `X-Forwarded-For`、`Sec-*`、Host、hop-by-hop 头，也不允许把 API Key、Token、Secret 作为附加头保存。
5. 跨 origin 重定向重新使用安全头集合，剥离 `Authorization`、`Cookie`、`Origin`、`Referer`、代理凭据及名称中包含 API key/token/secret 的头。
6. Profile 使用乐观版本号更新。正在被信息源使用的 Profile 可以编辑并热更新，但不能停用或删除；删除采用软归档。系统默认 `header_default` 永不删除。
7. UI、OpenAPI 和 PostgreSQL 是同一控制面契约。附加头使用 JSONB 持久化，列表返回使用数量和版本，便于管理员在修改前判断影响范围。

## 取舍

- 统一入口降低了 Collector 的重复逻辑和请求头漂移，但所有 first-party 请求必须经过该策略，新增 Collector 需要显式接入并补测试。
- 透明 FinBot 身份不能解决上游 CAPTCHA、WAF 或 IP 信誉问题；系统保持合规的 fail-closed 行为，不用伪装换取短期成功率。
- 热更新提高运维效率，但更新后的 Profile 可能改变多个来源的行为，因此 UI 显示使用数量、版本，并在停用/删除前强制解绑。
- 当前是单管理员控制面，不引入租户级 Profile 隔离；如果未来支持多租户，应把所有权和授权边界作为新的数据契约设计，而不是复用当前全局表。

## 回滚

先停止创建新的自定义 Profile 并把来源切回 `header_default`，再回滚应用和 UI；数据库 050 的 rollback 会移除来源绑定列和 Profile 表，但历史采集证据不受影响。生产回滚前必须确认没有仍使用自定义 Profile 的活动来源。
