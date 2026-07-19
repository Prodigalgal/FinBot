# 双内核代理网关

## 目标

让每个代理池可在后台选择 `SING_BOX` 或 `XRAY` 内核并热加载，统一向业务服务暴露 HTTP proxy 与健康接口。四台新建 VLESS REALITY 节点用于恢复交易所代理池；既有 HY2 节点与 Firecrawl 路由不被静默删除。

## 范围

- proxy gateway 镜像同时内置 sing-box 与 Xray 固定版本二进制。
- 数据库、Java 控制面、Python runtime、健康状态和 Web UI 增加显式 `engine`。
- sing-box 支持 VLESS 与 Hysteria2；Xray 支持 VLESS，遇到 Hysteria2 明确拒绝配置。
- VLESS REALITY URI 的 `flow` 进入类型模型并传递给两种内核。
- 内核切换必须重新校验配置、重启子进程并重新执行目标探测。

## 非目标

- 不让业务服务直接调用 sing-box 或 Xray。
- 不在公开仓库、GitOps 清单、日志或 API 响应中保存/回显节点 URI。
- 不以 Xray 替换 HY2 服务端。

## 影响文件

- `services/proxy-gateway/`：内核抽象、Xray 配置生成、进程生命周期、镜像与测试。
- `services/backend/`：Liquibase、控制面领域类型、持久化、HTTP 契约与 workspace。
- `apps/web/`：代理池内核选择、运行态展示与组件测试。
- `docs/decisions/028-dual-kernel-proxy-runtime.md`：架构决策。

## 验收标准

- UI 能保存并热切换代理池内核。
- 健康接口和 Java 状态返回实际内核。
- Xray 生成的 VLESS REALITY 配置通过 `xray run -test`；sing-box 配置通过 `sing-box check`。
- Xray 选择遇到 Hysteria2 时返回无敏感值的明确错误。
- 四个 VLESS 节点均能经代理访问 Bybit Demo 并返回 HTTP 200、`retCode=0`。

## 测试方式

- proxy gateway：pytest、ruff、mypy，覆盖双内核配置和热切换参数。
- backend：相关 Gradle 单元/集成测试和 Liquibase Testcontainers。
- Web：Vitest、TypeScript build。
- 生产：CI/GitOps 后检查 ArgoCD、Pod、健康接口与 Bybit 在线探测。
