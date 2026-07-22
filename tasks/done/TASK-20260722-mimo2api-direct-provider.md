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

## 完成证据

- Cloudflare DNS-only A/AAAA、MiMo2API HTTPRoute 与 streaming policy 已上线；公网入口不包含 Cloudflare 响应头。
- FinBot Liquibase `060` 已执行，生产 `finbot_v2.provider_mimo_default` 指向 `https://mimo2api-direct.mnnu.eu.org/v1`。
- `PublicAiProviderEndpointPolicy` 统一覆盖创建、更新、已保存测活、草稿测活和运行时解析，拒绝 HTTP、K8S Service、回环、私网、链路本地和 IPv6 ULA 地址。
- ADR-034 新增 `application.<feature>.validation` 职责规范；守卫按类型职责校验，不维护 Application 子目录白名单。
- 本机 MiMo Code 使用公网直连域名，模型能力解析为 `reasoning=true`、`tool_call=true`，`.mimocode/` 本地保留并由 Git 忽略。
- 公网直连 `mimo-v2.5-pro` 最小 completion 在 4.81 秒完成，返回 `stop`；MiMo Code 完整代理会话曾因上游排队超过 6 分钟，未发现 DNS/TLS/认证失败。
- Java 26 `clean test bootJar` 通过；GitHub Actions run `29909732061` 全绿。
- GitOps revision `a5585ba64a92a172cc8ddf3ab3d74685af8aa962`，Argo `Synced/Healthy/Succeeded`；9 个 Pod 全部 `1/1 Ready`、重启为 0，公网 health 为 `200/UP`。
- 生产 4 个 enabled Provider 的非法 HTTPS 计数为 0；MiMo 使用 `mimo2api-direct`，GPT/Grok/Gemini 使用 `sub2api-direct`。

## 兼容与回滚

- 历史 Liquibase `012` 保留不可变 checksum，其中的旧集群地址会在应用启动前由 `060` 覆盖，运行时策略也会拒绝该地址。
- 如需回滚代码，可回退 FinBot 镜像；如需回滚入口，可移除 direct DNS/Route 并新增显式迁移恢复原公网 Cloudflare 域名，不修改已执行迁移文件。
