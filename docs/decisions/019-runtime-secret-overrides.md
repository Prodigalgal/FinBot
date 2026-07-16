# ADR-019: 运行时 Secret 使用加密 Override 和 ENV fallback

## 状态

Accepted

## 决策

所有可热更新的外部凭据使用统一资源归属和解析顺序：`DATABASE_OVERRIDE > ENVIRONMENT_FALLBACK > UNCONFIGURED`。数据库 Override 使用 AES-256-GCM，加密 AAD 包含 scope、target ID 和 secret name；主密钥由 `FINBOT_RUNTIME_SECRET_MASTER_KEY` 在启动时注入，不进入数据库或 UI。

AI Key 归属 Provider Profile。Provider 可以登记多个模型，所有模型共享该 Provider 的凭据；模型配置只保留模型名、费率、能力上限和启停状态。多个同厂商账户使用多个 Provider Profile 表达，不通过模型名拆分 Key。

Provider 的数据库 Override 统一使用 `(AI_PROVIDER, providerId, API_KEY)` 定位。可选 bootstrap fallback 只保留一个 `FINBOT_AI_PROVIDER_KEYS_JSON` JSON 对象，键为 `providerId`；不为 Sub2API、Gemini、Grok、MiMo 或任何模型家族定义专用 Key 变量。Provider/Model API 也不暴露内部 fallback ENV 名称。

同一原则适用于其他应用资源：交易所使用 `(EXCHANGE_ACCOUNT, accountId, API_KEY|API_SECRET)` 和 `FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON`；信息源、代理路由、代理网关分别按 `sourceId`、`routeType`、`gatewayId` 定位。Gate/Bybit、Firecrawl 或具体节点池名称都不是凭据 schema 的一部分。

Provider 删除采用受保护软删除：只要 DRAFT/PUBLISHED 工作流节点、角色模板或交易执行阶段仍引用该 Provider，删除即冲突；解绑后归档 Provider、停用其模型并清除数据库热凭据，历史研究和调用记录继续可追溯。

数据库、管理员引导、Session 和内部服务认证属于 bootstrap dependency，继续由 K8S Secret/ENV 管理。依赖数据库和认证才能工作的后台不能反向负责这些 Secret，否则会形成无法启动和无法恢复的闭环。

## 后果

- 日常 Provider、代理、测试交易账户和信息源凭据轮换不再要求 Pod 重启。
- ENV 仍可完成首次部署、灾难恢复和清除 Override 后的 fallback。
- API 和 UI 永远无法读取旧明文，只能覆盖或清除；审计使用不可逆指纹。
- 主密钥轮换需要受控的 re-encryption 运维流程，不属于普通 UI 热更新。
