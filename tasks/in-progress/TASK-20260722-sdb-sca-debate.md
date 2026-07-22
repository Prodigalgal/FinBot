# TASK-20260722：SDB-SCA 辩论协议升级

## 目标

把默认 AI 辩论从顺序可见、单 Chair 裁决升级为可配置、可恢复、可审计的 SDB-SCA，
并让研究与模拟交易下游使用确定性的共识结果。

## 范围

- 领域模型、Liquibase 迁移、持久化端口与适配器。
- 提案、评审、修正、投票、聚合的 barrier 状态机。
- 角色归一 Schulze、顺序敏感检测和预测数值聚合。
- OpenAPI、工作流编辑器、运行态阶段与社会选择可视化。
- 旧 Chair 兼容读取、默认工作流迁移和自动交易失败关闭。

## 非目标

- Redis/MQ、多副本协调、密码学秘密计算。
- 在本任务中删除历史 Chair 数据或迁移历史结论。

## 关键文件

- `services/backend/finbot-domain/.../debate`、`.../consensus`
- `services/backend/finbot-application/.../workflow`
- `services/backend/finbot-infrastructure/.../workflow`
- `services/backend/finbot-bootstrap/src/main/resources/db/changelog`
- `contracts/finbot-control-plane.openapi.yaml`
- `apps/web/src/features/workflow`

## 验收标准

- 需求 38 的协议不变量和六项验收标准全部由自动化测试覆盖。
- Java 全量测试、PostgreSQL 集成测试、OpenAPI/Web 契约、前端测试和构建通过。
- 交叉审计确认没有同阶段信息泄漏、单一裁判覆盖或同角色席位权重放大。
- 默认工作流和生产运行态仅在 CI/GitOps 验证后切换。

## 状态

- [x] 需求与 ADR 固化
- [x] 领域模型与数据库账本
- [x] barrier 编排与恢复
- [x] 社会选择与预测聚合
- [x] API 与前端可视化
- [x] 全量测试和生产验收
- [ ] MiMo/Grok 外部 Agent 可采信交叉审计

## 当前验证

- Java 全量测试通过；新增 phase barrier/重放、身份泄漏守卫和 SDB 交易失败关闭直接测试。
- Web 全量组件测试、生产构建和控制面契约检查通过；走势预测面板展示角色归一后的三方向概率。
- GitHub Actions `29940758821` 全部通过：Java 26、PostgreSQL 18、量化/Browser Worker、OpenAPI、22 项 Web 组件测试、生产构建、Playwright 系统 smoke、Trivy 和 Cosign 均成功。
- 生产已运行 `main@e714266` 对应镜像；ArgoCD `Synced / Healthy`，Liquibase `67/67`，默认 v9 为 `PUBLISHED / SDB_SCA_V1`，席位间非 `EXCLUDE` 内容边为 0，公网登录后 13 页面 smoke 通过。
- MiMo 降低启动 skill 后不再出现上下文过长，但 MiMo2API 返回空工具调用；Grok 先遇到上游 429，随后重试会话持续挂起，尚无可采信外部交叉审计输出。
