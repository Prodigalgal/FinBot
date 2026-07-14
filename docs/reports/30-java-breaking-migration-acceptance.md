# Java Breaking Migration 验收记录

日期：2026-07-14

## 结论

Java 26 主系统、PostgreSQL 权威存储、Python Quant、代理控制面和 React `/api/v2` 管理台已形成可发布的生产候选。当前剩余门禁只有 GitHub Actions 镜像发布、Argo CD 切流和生产运行态观察。

## 数据迁移证据

- 生产 PVC 的 SQLite 快照只读导入到隔离 PostgreSQL：71 张表。
- `source_row_count=129244`，`archived_row_count=129244`，差异为 0。
- 强类型映射 11755 行：产品 2575、交易所合约 3705、别名 5063、Kline 412。
- 导入器按源文件 SHA-256 幂等，逐表提交并支持中断续跑；失败状态使用独立连接记录，不覆盖原始异常。
- 清理仅在导入成功并写入完成标记后执行；活动 SQLite、WAL/SHM 和旧 `finbot*.tar.gz` 归档均在清理范围内。

## 运行时证据

- Liquibase 11 个 changeset 在空 PostgreSQL 完整执行，二次启动无待执行项。
- Java 启动、Worker 注册和 Actuator health 通过；Spring 默认临时用户自动配置已禁用。
- 8 个核心页面完成真实登录和 API 加载；桌面 1536x1024 与移动 390x844 无横向溢出，浏览器无 warning/error。
- Firecrawl 与 Exchange 代理分别独立部署；无路由或无节点时 fail-closed。

## 发布与回滚

- Argo CD 按 freeze、schema、history、runtime 的 sync wave 顺序切流。
- 清理旧 SQLite 前创建 Longhorn 用户快照；该快照是旧系统数据回滚点。
- 新 PostgreSQL 不做自动反向写回。若发布失败，恢复旧镜像和 Longhorn 快照，保留新库供事故分析。
