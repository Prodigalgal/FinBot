# ADR 020: 请求级代理轮换与可管理信息源

## 状态

Accepted，2026-07-16。

## 背景

Firecrawl keyless 采集曾在同一批次出现大量 HTTP 403/429。线上代理池虽然加载了多个节点，但 sing-box `urltest` 会持续选择单一最快出口，无法提供“每次采集请求更换节点”的语义。同时，信息源仍主要由 Liquibase 固化，管理员无法在不发版的情况下新增、修正或停用来源。

## 决策

1. sing-box 为每个选中节点创建独立的 localhost `mixed` inbound，并将该 inbound 固定路由到对应 outbound。
2. Proxy Gateway 在公开代理端口前增加 TCP round-robin relay；每个新 TCP 连接原子地选择下一个节点。
3. Firecrawl 每次尝试创建独立 `HttpClient`，避免复用上一条代理隧道；403、429 和可恢复 5xx 最多尝试三次。
4. `InformationSource` 作为 PostgreSQL 管理资源开放 CRUD、启停和在线测试。删除采用 `deleted_at` 软删除，保留采集、证据和研究历史，同时清除该资源的数据库凭据覆盖。
5. 信息源凭据仍通过通用 `(INFORMATION_SOURCE, sourceId, API_KEY)` 解析，普通配置和 Secret 生命周期分离。

## 取舍

- 轮换粒度是 TCP 连接，不承诺单一长连接中的多个 HTTP 请求切换节点。因此 Firecrawl 调用方必须显式建立独立客户端连接。
- 节点失效由 Firecrawl 有限重试切换到后续节点；不在 relay 内重放已发送字节，避免非幂等请求被隐式重复。
- 软删除增加查询过滤要求，但保留了审计与研究可复现性，并避免破坏历史外键。
- Gateway 健康端点公开 `rotationMode`、`assignedConnectionCount` 和 `lastAssignedNodeIndex`，用于区分“节点已加载”与“请求确实发生轮换”。

## 验证

- Python 测试覆盖轮换顺序、热替换和真实 TCP relay 到不同目标。
- Java 测试覆盖每次请求新建客户端、403 后重新走代理并成功恢复、异常采集状态收口。
- PostgreSQL 集成测试覆盖信息源新增、乐观锁更新、历史采集保留、凭据清理和软删除。
- 生产验收采样连续出口，并检查代理健康计数、Firecrawl 在线测试和后续批次 403/429 分布。
