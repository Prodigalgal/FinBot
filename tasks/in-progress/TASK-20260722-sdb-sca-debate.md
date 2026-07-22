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
- [ ] 全量测试、交叉审计和生产验收

## 当前验证

- Java 全量测试通过；新增 phase barrier/重放、身份泄漏守卫和 SDB 交易失败关闭直接测试。
- Web 全量 21 项组件测试、生产构建和控制面契约检查通过；既有 Settings 慢用例已改为等价的表单 change 事件，消除逐字符输入导致的套件级超时。
- 本机未安装 Docker，Testcontainers PostgreSQL 场景未在本轮真实启动；必须由 CI 或远端 PostgreSQL 补齐。
- MiMo 已通过隔离 HOME 将启动 skill 从 178 降至 14，不再出现上下文过长，但 MiMo2API 工具调用仍超时；Grok 单轮 CLI 同样超时，尚无可采信交叉审计输出。
