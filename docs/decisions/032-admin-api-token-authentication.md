# ADR 032：管理员 API Token 认证

- 状态：Accepted
- 日期：2026-07-21

## 背景

FinBot 目前只有管理员 Cookie 会话，适合后台浏览器但不适合脚本、自动化和外部控制面调用。系统仍是单管理员模型，因此现阶段不需要 OAuth、多租户或细粒度 scope，但必须保留可吊销、可过期和可审计的机器凭据。

## 决策

- Token 使用 `finbot_pat_` 前缀和 256 位随机主体，通过 `Authorization: Bearer <token>` 传递，并获得与管理员会话相同的 `ROLE_ADMIN`。
- 数据库只保存完整 Token 的 SHA-256 摘要和 16 位指纹；明文只在创建响应中返回一次，不支持事后回显。
- Token 支持名称、可选到期时间、最后使用时间、吊销时间和乐观锁版本。到期或吊销后立即失效，记录永久保留。
- Bearer 请求不依赖 Cookie，因此免除 CSRF；Cookie 会话的写请求继续强制 CSRF。请求一旦带有 `Authorization`，无效 Bearer 不得回退到同请求的 Cookie 会话。
- 全局 OpenAPI 安全要求同时声明 `adminSession` 和 `adminApiToken` 两种可选认证方式。

## 取舍

- Token 暂不划分 scope，等价于管理员全权限。这满足当前单管理员自动化目标，但任何泄露都具有完整控制面风险。
- SHA-256 适用于高熵随机 Token；即使数据库泄露，也不存在可行的穷举空间。若未来支持低熵用户自定义 Token，必须改为带服务端密钥的 HMAC 或密码哈希。
- `last_used_at` 按时间窗口降频更新，避免每个 API 请求都写数据库；吊销使用独立版本，不受使用时间更新影响。

## 回滚

先吊销所有 Token，再回滚应用镜像与 Liquibase 057。回滚会删除 `admin_api_token` 表；Cookie 会话认证不受影响。
