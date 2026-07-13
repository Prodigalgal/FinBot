# Migration 009: SQLite 到 PostgreSQL

## 前置条件

- GitHub Actions、DockerHub 和 Argo CD 均可用。
- `finbot-secrets` 已包含 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`。
- PostgreSQL StatefulSet Ready，目标数据库为空。
- 当前自动循环已停止接受新请求，队列中没有 running request。

## 切换顺序

1. 记录当前 Git revision、GitOps revision、镜像 tag、SQLite 大小和逐表计数。
2. 生成迁移前 SQLite 备份并在集群外保存，核对 SHA-256。
3. 停止 FinBot Deployment，确认 Web/Worker/Metrics 均已退出。
4. 运行 `python -m finbot.cli.migrate_postgres --sqlite-path ... --database-url-env FINBOT_DATABASE_URL`。
5. 迁移器初始化 schema、复制全部表、校验逐表计数并写迁移标记。
6. 启动 PostgreSQL-only FinBot，验证 readiness、Worker heartbeat、SSE、登录和关键查询。
7. 保留 SQLite、PostgreSQL 迁移报告和迁移前备份，至少到连续三轮自动循环成功。

## 失败处理

- 任一目标表非空、schema 创建失败、row count 不一致或 sequence 校验失败时立即终止。
- 失败时不启动新应用，不删除或修改源 SQLite。
- 回退旧 GitOps revision 后只允许旧版本读取原 SQLite；PostgreSQL 保留用于故障分析。

## 验证命令

```powershell
python -m unittest discover -s tests -v
python -m compileall -q finbot
python scripts/secret_scan.py
cd web-ui
npm run build
```

生产还需验证 PostgreSQL `pg_isready`、逐表计数报告、SSE heartbeat、Argo CD、Pod restart 和 Prometheus 指标。

## 生产前演练记录

- 时间：2026-07-13。
- 源：生产 SQLite 一致性备份，`integrity_check=ok`，71 张业务表、124,769 行。
- 结果：隔离数据库 `finbot_rehearsal` 全表迁移成功，逐表计数一致；第二次执行返回 `already_migrated`。
- 扩展 schema：覆盖 `oms_orders`、`oms_order_events` 等运行时模块表，并按外键依赖顺序创建。
- 运行契约：`OmsRepository`、`ExperimentRegistry`、`FinBotMetricsCollector` 在 PostgreSQL 上验证通过。
- 清理：演练 Pod 和演练数据库已删除；正式 `finbot` 数据库保持 0 张关系表，等待 GitOps 切换。
- 最终切换前备份：`finbot-pre-postgres-20260713T112757+0800.tar.gz`，SHA256 `c4b2da834f4f478c0da42f71c7310539725236a681497f97f56ba59bec56e453`，集群 PVC 与本机私有备份目录各保留一份。
