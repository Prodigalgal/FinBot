# TASK-20260716: 统一运行时配置控制面

## 目标

全面审计并统一 FinBot 配置管理，使外部凭据和代理配置可在后台安全热更新、热测试，同时保留必要的 bootstrap ENV 边界。

## 范围

- 配置分类、加密 Override、版本和审计。
- Provider、代理路由、测试交易账户、信息源凭据运行时解析。
- Provider/Model 自由新增、引用提示、受保护软删除和通用 `API_KEY` 归属。
- 交易所账户及其他应用资源的厂商无关凭据映射与 UI ENV 字段清理。
- Firecrawl 请求级代理节点轮换、独立连接、有限重试与轮换状态观测。
- 信息源创建、编辑、启停、软删除、在线测试、历史保留和资源级加密凭据。
- 配置中心 UI、连通性测试和生效来源状态。
- Liquibase 032、K8S 主密钥、契约和线上验证。
- Liquibase 033 信息源归档契约、代理出口轮换和 Firecrawl 线上成功率验证。
- 定时任务重叠抑制：同一 schedule 存在 `PENDING`/`CLAIMED` 实例时只推进调度游标，不重复入队。
- Worker 周期恢复过期租约，覆盖滚动发布时旧 Pod 在新 Pod 启动后才停止形成的孤儿任务。

## 非目标

- UI 不管理数据库密码、运行时 Secret 主密钥、管理员引导密码、Session Secret和内部服务令牌。
- 不开放实盘交易。

## 状态

In progress. 代码、契约、数据库迁移、单副本部署、UI/API smoke、定时任务重叠抑制和运行期租约恢复均已完成并在线验证；生产运行 `4ac7113`，ArgoCD revision `14639545094bac93aa589968ba3871b350f422b3` 为 `Synced/Healthy`。剩余边界是外部代理节点质量：当前 Firecrawl keyless 出口仍会触发 IP 风控 403，Bybit 代理仍存在 TLS EOF；需更换稳定节点池或为 Firecrawl 配置 API Key 后再关闭本 Goal。
