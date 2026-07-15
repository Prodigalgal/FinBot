# 功能等价迁移执行计划

状态：In progress

基线日期：2026-07-15

## 完成定义

只有当 `docs/requirements/31-feature-parity-migration.md` 的矩阵全部具有代码、自动测试、浏览器证据和生产证据时，本计划才能标记完成。旧 Python 运行时不回迁；所有新入口使用 `/api/v2`。

## P0：恢复可信可用性

- [x] 对照旧版页面、API 和需求建立完整迁移矩阵。
- [x] 在线复现登录后 PUT 的 CSRF 403，并记录 token 路径证据。
- [ ] 发布 SPA CSRF handler、cookie-first React header 和回归测试。
- [ ] 恢复模拟账户“账户概览 / 操作历史”独立视图。
- [ ] 扩展统一审计读取：AI 决策、建议、反思、风控、预估、OMS、提交尝试、交易所事实、对账。
- [ ] 补账户/来源/阶段/状态/标的/时间筛选、cursor 分页、来源完整性和展开详情。
- [ ] 发布仅研究产品预估交易，确认 `ESTIMATED` 无 `oms_order` 和私有交易所请求。

## P1：恢复研究和分析闭环

- [ ] 工作台增加 readiness、最新结论、异常和快速动作。
- [ ] 自动研究增加状态、下次运行、手动触发、进度和结果工作区。
- [ ] 复核与效果增加反馈、运行对比、checkpoint 续跑和效果视图；不恢复测试网硬人工审批。
- [ ] 市场分析增加 Java 异步命令、Python quant 调用和结构化结果。
- [ ] 量化验证增加无副作用杠杆/仓位/风控 preview。
- [ ] 采集与处理增加分阶段任务、证据血缘、失败重试详情。
- [ ] 运行报告增加结构化摘要与原始审计展开。

## P2：恢复配置、工作流和运营闭环

- [ ] 快速启用档案和 readiness apply 审计。
- [ ] AI 服务、任务绑定、提示词、角色、模型刷新、费率和实验视图。
- [ ] 工作流 rollback、estimate、node test、plan、run history、checkpoint resume 和 learning。
- [ ] 网络诊断工作区和脱敏后台诊断。
- [ ] 全局 operations SSE、数据新鲜度和断流回退。
- [ ] URL 页面状态、移动页面选择器和全页面人类可读状态收口。

## 验证门禁

每个发布批次顺序执行：

1. Java `clean test build` 和真实 PostgreSQL Liquibase fresh/upgrade。
2. Python Ruff、mypy、pytest 和 OpenAPI 契约测试。
3. React TypeScript clean build、组件/交互测试和 1536x1024、390x844 Playwright。
4. secret scan、GitHub Actions、ARM64 镜像、Trivy、Cosign 和 GitOps revision。
5. Argo CD `Synced/Healthy`、单副本 Pod Ready/零重启、数据库 changeset 数量。
6. 登录、CSRF 写、账户历史、即时研究、SSE、工作流、设置保存和预估交易生产 smoke。

## 当前证据

- 线上登录返回 200；登录响应 token 与 cookie token 不同。
- 分别使用两种 token 执行无副作用账户配置冲突 PUT，均返回 403 CSRF。
- 当前模拟交易页只有账户/持仓、AI 自动化列表和底部 6 列交易所事实表；旧版独立历史筛选、分页、来源状态和展开详情不存在。
- 预估交易本地领域、应用、Liquibase 和 React build 已通过；真实 PostgreSQL fresh migration 为 25/25 changesets，尚未提交发布。

## 回滚点

- 当前生产镜像/Git revision：以发布前 Argo CD Application revision 为准记录。
- 数据库变更仅 additive；回滚应用镜像时保留新增表，不删除预估或审计事实。
- CSRF 修复不得通过关闭 CSRF 回滚；若 handler 异常，回滚整个应用镜像。
