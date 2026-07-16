# TASK-20260716：审计 P1-P3 工程加固

- 状态：进行中
- 目标：完成 Worker、行情、交易恢复测试、OpenAPI、代理 TLS 和 Web 测试六项审计修复。
- 非目标：不改变研究和交易业务规则，不做 UI redesign，不启用真实盘交易。
- 验收：见 `docs/requirements/33-audit-p1-p3-hardening.md`。
- 回滚点：本任务独立提交；生产配置维持单副本，可回退至上一镜像 tag。
