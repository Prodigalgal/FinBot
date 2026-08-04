# TASK-20260804：Any2API 研究流程路由

## 目标

将默认工作流的 AI 研究阶段统一接入 Any2API，并以异构上游模型维持多席位独立研究；
SDB-SCA 确定性社会选择和最终执行机器人保持不变。

## 范围

- 新增一个厂商无关的 Any2API Provider Profile 和已验证模型目录。
- 发布默认工作流 v10，迁移清洗、压缩、验证和研究席位。
- 增加模型级输出上限参数能力，严格适配上游 `protocolContract`，不静默丢弃参数。
- 节点诊断复用正式重试/fallback 执行策略，并放宽异步 HTTP 等待窗口。
- 通过运行时密钥覆盖保存 API Key，仓库和 Liquibase 不保存明文。
- 补充 Liquibase/PostgreSQL 契约与生产流式测活。

## 非目标

- 不修改 `SOCIAL_CHOICE` 节点、SDB-SCA 协议或投票权重。
- 不修改 `EXECUTION_REVIEW` 节点的 GPT 模型、reasoning 或反思流程。
- 不迁移采集、量化计算、交易所数据和 AI Web Search 信源。
- 不启用当前真实调用失败的 Qwen 或额度异常模型。

## 影响文件

- `services/backend/finbot-infrastructure/.../db/changelog`
- `services/backend/finbot-domain`、`finbot-application`、`finbot-bootstrap`
- `contracts/finbot-control-plane.openapi.yaml`
- `apps/web/src/SettingsPage.tsx`
- `services/backend/finbot-infrastructure/.../Liquibase*Test.java`
- `tasks/current.md`

## 验收标准

- Any2API 仅保存一份 Provider API Key，模型与 Key 不重复绑定。
- 五个逻辑研究角色各保留两个异构模型席位，主模型与 fallback 来自不同上游家族。
- 清洗、压缩、验证和研究席位全部使用 Any2API；Qwen 不进入默认路由。
- GLM 继续发送协议默认输出上限；DeepSeek、LongCat、MiMo、MiniMax 按上游能力不发送不支持的 token-limit 参数。
- `SOCIAL_CHOICE` 无 AI binding；两个最终执行节点与 v9 完全一致。
- Liquibase offline validation、PostgreSQL integration test 和生产 Provider probe 通过。

## 状态

- [x] Any2API 目录、管理面和真实流式调用审计
- [x] Provider/模型目录与 v10 工作流迁移
- [x] 模型级 token-limit 能力契约与 UI 配置
- [x] 自动化测试
- [ ] CI/GitOps 与生产运行态验证
