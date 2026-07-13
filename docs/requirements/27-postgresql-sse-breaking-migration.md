# PostgreSQL 与 SSE 一次性迁移需求

## 目标

- 生产运行态从 SQLite 一次性迁移到 PostgreSQL，消除单文件写锁和单 Pod 数据库约束。
- 用 Server-Sent Events 持续推送运行状态与即时研究进度，避免 Cloudflare 等待普通 HTTP 响应超时。
- 保留完整历史数据、审计链、工作流版本、模拟交易和 AI invocation 记录。

## 范围

- 新增 PostgreSQL Store、连接池、schema 初始化和 SQLite 全表迁移器。
- 生产启动必须提供 `FINBOT_DATABASE_URL`；缺失时 readiness 和进程启动均失败。
- 新增运行状态 SSE 与即时研究 SSE；前端以 EventSource 为主，断线后自动重连并降级为低频 HTTP 拉取。
- K8S 增加 FinBot 专属 PostgreSQL StatefulSet、Service、Longhorn PVC、Secret 引用、探针和网络策略。
- 一次性停机迁移完成后，Web、Worker 与 Metrics 全部只连接 PostgreSQL。

## 非目标

- 不双写 SQLite/PostgreSQL，不保留生产运行时后端开关。
- 不复用 `ircs-prod` PostgreSQL，不跨 namespace 共库。
- 不在本阶段横向扩容 Web；单管理员 Session 仍为进程内状态。
- 不删除旧 SQLite 文件和迁移前备份。

## Breaking Changes

- `FINBOT_DATABASE_URL` 成为生产必填 Secret。
- SQLite 仅允许迁移 CLI 和测试夹具读取，生产 `build_store()` 不再创建 SQLiteStore。
- 原 SQLite 文件备份命令不再代表生产数据库备份；PostgreSQL 使用 `pg_dump`/`pg_restore`。
- K8S 首次启动会在 initContainer 中执行迁移；校验失败时应用容器不得启动。

## SSE 契约

- `GET /api/v1/stream/operations`：事件类型 `snapshot`、`heartbeat`、`error`。
- `GET /api/v1/instant-research/{request_id}/events`：事件类型 `session`、`heartbeat`、`complete`、`error`。
- 首字节必须立即发送；heartbeat 间隔不超过 15 秒。
- 响应头包含 `text/event-stream`、`Cache-Control: no-cache, no-transform` 和 `X-Accel-Buffering: no`。
- 事件仅包含现有授权用户可读取的数据，不包含 API key、代理凭据或隐藏推理。

## 数据迁移验收

- 迁移前生成 SQLite 文件备份和 SHA-256。
- PostgreSQL 必须为空；迁移器拒绝覆盖已有业务数据。
- 所有非 SQLite 内部表逐表复制并核对 source/target row count。
- 所有表、显式 index、唯一约束和自增 sequence 可用。
- 写入 `finbot_schema_migrations` 迁移标记及逐表计数 JSON。
- 随机抽查 autonomous run、AI debate、trade decision、workflow version、paper execution 和 watchlist 主键。

## 运行验收

- Python 全量测试、前端 build、Secret scan 通过。
- PostgreSQL 集成测试覆盖 schema、upsert、租约竞争、迁移幂等和 SSE 断线重连。
- Argo CD `Synced/Healthy`，PostgreSQL 与 FinBot Pod Ready、零异常重启。
- Worker heartbeat 不再出现 `database is locked`。
- 公网 SSE 连接持续超过 Cloudflare 普通请求超时窗口且收到 heartbeat。
