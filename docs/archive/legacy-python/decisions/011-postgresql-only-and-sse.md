# ADR 011: PostgreSQL-only 生产存储与 SSE 状态传输

状态：Accepted

日期：2026-07-13

## 背景

当前 Web、Worker 与 Metrics 共享 SQLite/PVC。长研究任务与高频状态查询会竞争同一文件锁，线上已经出现 `database is locked`；前端同时以 2 秒和 6 秒轮询大 payload，经过 Cloudflare 时会出现 504 或连接重置。

## 决策

1. 生产存储一次性迁移到独立 PostgreSQL，不提供双写或长期双后端模式。
2. 业务 Store 通过 Psycopg 连接池访问 PostgreSQL；SQLiteStore 只作为迁移源和测试夹具保留。
3. 首次 K8S 切换由 `Recreate` 停止旧 Pod，initContainer 将静态 SQLite 快照迁入空 PostgreSQL；逐表校验成功后才启动应用。
4. 长进度传输采用 SSE。连接先发送 `connected`/heartbeat，再发送状态变化；前端不再周期性请求完整状态 payload。
5. PostgreSQL 采用 FinBot 独立 StatefulSet 与 20Gi Longhorn PVC，不复用其他业务数据库。

## 取舍

- 一次性切换减少长期兼容复杂度，但需要短暂停机和严格回滚窗口。
- SSE 是单向状态通道，命令仍使用普通 POST/PUT；这与研究任务的异步模型匹配。
- 初期仍保持单 Web 副本，因为 Session 尚未共享；PostgreSQL 先解决数据锁和后续拆 Pod 的前置条件。
- 迁移适配层暂时复用现有 Store 方法契约，避免同时重写全部领域服务；后续再按聚合拆 Repository。

## 回滚

- 迁移失败：initContainer 阻止应用启动，回退到迁移前镜像和 GitOps revision，挂载未删除的 SQLite/PVC。
- 切换后发现问题：停止新版本，导出 PostgreSQL 事故快照，再回退旧镜像与迁移前 SQLite 只读快照；不尝试自动反向同步新写入。
- PostgreSQL PVC 与原 SQLite 文件均不得在验收期删除。
