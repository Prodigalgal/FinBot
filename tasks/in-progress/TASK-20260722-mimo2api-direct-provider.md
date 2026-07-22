# TASK-20260722：MiMo2API 公网直连与 FinBot Provider 切换

## 目标

为 MiMo2API 增加绕过 Cloudflare 的公网直连域名，并让 FinBot 的 MiMo Provider 使用该入口，降低长首字节与长流式请求被代理层截断的风险。

## 范围

- Cloudflare DNS-only A/AAAA 记录。
- MiMo2API HTTP/HTTPS `HTTPRoute` 双 hostname。
- GitOps 运行文档和 streaming policy 说明。
- FinBot Liquibase 迁移及 PostgreSQL 集成断言。
- 生产 Provider 热更新、模型探测与真实 completion。
- AI Provider 公网 HTTPS 地址策略：创建、更新、测活与运行时统一拒绝集群内、回环和私网端点。
- 本机 MiMo Code 使用相同的公网直连域名。

## 非目标

- 不改变 API Key、模型、角色、工作流、费率、并发或超时配置。
- 不修改 Deployment、Service、PVC、Secret、共享证书或 Envoy 超时 component。
- 不移除现有 Cloudflare 入口。
- 不改变 FinBot 内部 backend、quant、database、SearXNG 等组件使用 K8S Service 的通信方式。

## 影响文件

- `services/backend/finbot-infrastructure/.../060-mimo2api-direct-endpoint.sql`
- `services/backend/finbot-infrastructure/.../db.changelog-master.yaml`
- `services/backend/finbot-infrastructure/.../LiquibasePostgresIntegrationTest.java`
- `services/backend/finbot-application/.../configuration/validation/PublicAiProviderEndpointPolicy.java`
- 本机 `C:/Users/zzp84/.config/mimocode/mimocode.jsonc`。
- 私有 GitOps 的 `mimo2api/oracle/routes.yaml` 与运行文档。

## 验收标准

- `mimo2api-direct.mnnu.eu.org` 解析到 Oracle Gateway 且 `proxied=false`。
- HTTP 301、HTTPS TLS、`/v1/models` 和真实 `mimo-v2.5-pro` completion 成功。
- Argo CD `mimo2api` 为 `Synced/Healthy`；两条 Route 与 streaming policy 均 `Accepted=True`。
- 生产 `provider_mimo_default.base_url` 为 `https://mimo2api-direct.mnnu.eu.org/v1`。
- 所有 AI Provider 只能配置公网 HTTPS URL；集群内、回环、私网与明文 HTTP 地址 fail-closed。
- MiMo Code 配置解析结果及一次真实 `mimo-v2.5-pro` 调用均使用公网直连域名。
- Liquibase 集成测试证明新安装和升级后的默认值一致。

## 测试方式

- Kustomize render + server-side dry-run。
- Cloudflare API 与公共 DNS 查询。
- K8S/Argo CD/HTTPRoute/BackendTrafficPolicy 状态检查。
- PostgreSQL 集成测试、生产数据库核验、外部及 Pod 内网络 smoke、真实模型请求。
