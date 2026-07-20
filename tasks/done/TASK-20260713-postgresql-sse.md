# TASK-20260713 PostgreSQL + SSE breaking migration

状态：Done。生产数据库为 PostgreSQL `finbot_v2`，前端 `/api/v2` 与 Operations/Workflow SSE 已通过生产浏览器 smoke。

目标：一次性把生产数据库从 SQLite 切到 PostgreSQL，并以 SSE 替换高频完整状态轮询。

范围与验收以 `docs/requirements/27-postgresql-sse-breaking-migration.md`、`docs/decisions/011-postgresql-only-and-sse.md` 和 `docs/migrations/009-sqlite-to-postgresql.md` 为准。
