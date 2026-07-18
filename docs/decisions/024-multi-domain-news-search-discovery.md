# ADR 024：多领域新闻、SearXNG 与 AI Web Search

## 状态

Accepted，2026-07-18。

## 决策

1. 默认信源使用“官方结构化数据 + 独立新闻站点 + 搜索发现 + AI Web Search”四层架构，不依赖单一新闻聚合器或 Firecrawl。
2. 国内外新闻站点分别建立 `InformationSource`，保留品牌、URL、时间和证据层级；搜索摘要不能冒充原站正文。
3. 部署一个内部 SearXNG 单副本，通过来源级 `engine_shortcuts` 选择多个搜索引擎，而不是为国内、国际和新闻分别部署副本。Backend 将快捷码编译为官方支持的 `!shortcut` query syntax；不使用会被当前 Search API 忽略的 `engines=`。所有搜索出口经 `WEB_CRAWL` 代理，NetworkPolicy 禁止直连公网。
4. `SearxngSearchDiscoveryProvider` 只接受 allowlist 中的内部 HTTP Service，校验 engine shortcut、限制响应大小、重试次数和返回 URL；普通用户不能借此配置任意内网地址。
5. 模型原生搜索使用新 `AI_WEB_SEARCH` mode，并通过一对一 binding 引用已有 Provider/Model。Provider Key 保持厂商无关且只存一份；调用审计独立记录 token、引用数和错误。
6. Grok/Gemini AI 搜索默认关闭，只有实际网关工具协议测活通过后才启用。AI Web Search、SearXNG、GDELT、Firecrawl 是并列渠道，任何渠道失败都不会静默切换语义。
7. 默认目录追加 v3 manifest，不修改或删除 v1/v2 历史。新增来源回滚时软删除，避免破坏已经形成的证据外键。

## 取舍

- 单 SearXNG 副本符合当前无高可用、低成本部署约束，配置和观测更简单；代价是搜索发现会有单点中断，但官方 RSS/JSON 和现有证据不受影响。
- 元搜索减少单一引擎偏差，却会面对 CAPTCHA、区域限制和引擎 HTML 变化；因此健康检查必须区分进程 Ready 与结果可用。
- 生产 smoke 证明新闻专用引擎及 Baidu/Sogou 可能同时不可用；默认路由在同一 SearXNG 渠道内显式加入 Bing/DuckDuckGo general engine。该冗余是来源配置的一部分，不是跨渠道隐式 fallback，结果仍按实际引擎和 canonical URL 审计。
- 大量 RSS 提高覆盖面，也会带来重复、标题党和摘要截断；依靠 canonical URL、内容哈希以及后续多 Agent 清洗/压缩验证处理，而不是为每站维护脆弱 CSS 规则。
- AI Web Search 能补齐长尾和实时事件，但成本、协议和引用质量不稳定，所以默认关闭并保留独立审计，不能替代第一方来源。

## 回滚

048 rollback 仅移除 5 个默认来源的 general engine 冗余；047 rollback 仅把 6 个仍保持默认值的 SearXNG endpoint 恢复为 046 配置；046 rollback 删除 v3 manifest，恢复 Reuters/AP 和 Gate 的受保护配置，并对本次新增来源执行软删除；045 rollback 先删除 AI 审计/binding，将 AI 来源归档为禁用的 `SEARCH_DISCOVERY`，再恢复旧 mode constraint。SearXNG Deployment、Service、ConfigMap 和 NetworkPolicy 可随 GitOps revision 一并回退，不删除任何历史证据。
