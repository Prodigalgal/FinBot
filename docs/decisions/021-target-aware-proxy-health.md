# ADR 021: 目标感知的代理节点健康

## 背景

代理网关原先只校验 sing-box 本地监听端口。生产实测中，Exchange 的 4 个节点对所有 HTTPS 请求均重置连接，网关仍返回 `ready=true`；Firecrawl 的 32 个节点只有部分出口能通过 keyless IP 风控。仅做 TCP readiness 会把不可用节点持续放入轮转，并让配置面板误报正常。

## 决策

1. 每个网关使用部署时固定的 HTTPS 目标探测定义，通过各节点独立本地端口执行真实请求。探测 URL、方法、请求体和响应断言不接受运行时 API 输入，避免扩大 SSRF 边界。
2. 只有通过预期 HTTP 状态和响应体断言的节点进入公共 round-robin；轮转保留订阅中的原节点索引。全部失败时清空轮转目标并 fail closed。
3. 健康状态分别暴露 `serviceReady` 与 `ready`（出口可用）。Kubernetes `/health/ready` 使用 `serviceReady`，确保坏池仍能接收热配置；`/health/egress` 和 Java 控制面使用出口状态。
4. 状态只暴露节点总数、健康数量、原索引、失败分类、目标主机和时间，不暴露订阅、节点地址或凭据。
5. Firecrawl 使用真实 keyless scrape 响应验证 IP 风控；Exchange 使用 Bybit Demo 公共时间接口验证目标可达性。订阅刷新或 UI 重新加载时重新探测。

## 影响

- Firecrawl 不再靠随机三次重试碰撞少量可用 IP，失败节点在进入业务流量前即被隔离。
- Exchange 节点全部失效时，Pod 保持可管理但 UI 明确显示无可用出口，Bybit 调用继续 fail closed。
- 目标探测会产生有限外部请求；沿用网关刷新周期，避免持续高频扫描。
