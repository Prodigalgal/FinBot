# S3: 统一运行时配置控制面

## 目标

将外部服务凭据和代理路由从仅 ENV 注入升级为可审计的后台热配置。配置必须绑定业务资源而不是模型名称：AI Key 绑定 `AiProviderProfile`，模型只引用 Provider；交易所 Key 绑定 `ExchangeAccount`；代理 URL 绑定 `NetworkProxyRoute`；信息源 Key 绑定 `InformationSource`。

## 配置生命周期

| 类别 | 示例 | 管理方式 |
| --- | --- | --- |
| 运行时普通配置 | 模型费率、思考等级、超时、调度、风控、工作流、启停状态 | PostgreSQL 版本化配置，UI 热更新 |
| 运行时 Secret | Provider Key、模拟交易账户 Key/Secret、代理 URL、信息源凭据 | PostgreSQL AES-GCM 加密 Override，UI 热更新，ENV fallback |
| Bootstrap Secret | 数据库密码、运行时 Secret 主密钥、管理员引导密码、Session Secret、内部服务令牌 | K8S Secret/ENV，仅部署时更新 |
| 进程/基础设施参数 | 监听端口、数据库连接、Pod 资源、内部 Service URL | GitOps/ENV，明确标记需要滚动重启 |

## 范围

- 新增通用 `runtime_secret_override` 与无明文审计表。
- AES-256-GCM 加密，AAD 绑定 scope、target 和 secret name；主密钥仅从 bootstrap ENV 读取。
- 有效值优先级固定为 `DATABASE_OVERRIDE > ENVIRONMENT_FALLBACK > UNCONFIGURED`。
- API 只接受新值，不返回明文；查询仅返回来源、指纹、版本和更新时间。
- 后台支持设置、轮换、清除 Override；清除后立即回退 ENV。
- AI Provider 调用、模型探测、代理路由、交易所调用和信息源采集使用统一解析端口。
- AI Provider 可在 UI 新增、修改和软删除；创建时同时登记首个模型，后续模型只引用 `providerId` 并共享该 Provider 的 `API_KEY`。
- Provider 删除前统计 DRAFT/PUBLISHED 工作流节点、角色模板和执行机器人阶段的引用；有引用时必须先解绑，修改时 UI 明确警告影响范围。
- 所有 Provider 的 bootstrap fallback 仅使用 `FINBOT_AI_PROVIDER_KEYS_JSON` 这一通用映射，以 `providerId` 为键；应用层和 UI 不再出现厂商专用 Key 环境变量。
- 交易所账户统一使用 `FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON`，以 `accountId` 为键、`API_KEY`/`API_SECRET` 为凭据名；不得再按 Gate、Bybit 或交易产品命名 Key。
- 信息源、代理路由与代理网关分别使用资源级通用映射；对外 DTO 只暴露是否支持、有效来源、指纹与版本，不暴露 fallback ENV 名称。
- 保存后无需重启；探测接口必须使用刚保存的有效配置。
- UI 区分“已热配置”“ENV fallback”“未配置”，不把 ENV 名称或模型家族误当凭据归属。

## 安全与失败规则

1. 明文不得进入 API 响应、日志、异常、审计表、Git 或前端状态持久化。
2. 主密钥缺失时允许 ENV fallback 继续运行，但拒绝创建或解密数据库 Override。
3. 密文认证失败、版本冲突或目标不存在必须显式失败，不得静默回退到旧值。
4. Proxy URL 只允许 `http`/`https`，显示时移除 user-info、path、query 和 fragment。
5. Firecrawl `requireProxy=true` 的 fail-closed 约束不可由 UI 放宽。
6. 实盘交易写入仍永久禁止；交易所热凭据仅服务现有测试网/模拟账户。

## 验收标准

1. 一个 Provider 下的全部模型共享一个有效 Key；模型 DTO/API 不出现 Key 字段。
2. UI 轮换 Provider Key 后，模型探测和下一次 AI 调用立即使用新值。
3. UI 更新 Proxy URL 后，下一次路由解析和网络诊断立即使用新值。
4. 清除数据库 Override 后立即恢复 ENV fallback，来源状态正确变化。
5. 并发旧版本写入返回冲突，审计事件只记录动作和指纹。
6. PostgreSQL 集成测试覆盖加密 round-trip、错误主密钥、热更新、清除和版本冲突。
7. Java/Web/Python/OpenAPI/Kustomize/Secret scan 通过，并完成线上热更新与探测 smoke。
8. Provider/Model 创建、更新与删除契约不暴露 ENV 名称；无引用 Provider 采用软删除并保留历史调用外键，有引用 Provider 删除返回冲突。
9. Gate 与 Bybit 账户共享同一凭据解析路径，切换交易所或增加账户不需要新增 Java 分支或新增厂商专用 Secret key。
