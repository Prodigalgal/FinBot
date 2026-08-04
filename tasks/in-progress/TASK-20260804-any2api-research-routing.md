# TASK-20260804：Any2API 研究流程路由

## 目标

将默认工作流的 AI 研究阶段统一接入 Any2API，并以异构上游模型维持多席位独立研究；
SDB-SCA 确定性社会选择和最终执行机器人保持不变。

## 范围

- 新增一个厂商无关的 Any2API Provider Profile 和已验证模型目录。
- 发布默认工作流 v10，迁移清洗、压缩、验证和研究席位。
- 增加模型级输出上限参数能力，严格适配上游 `protocolContract`，不静默丢弃参数。
- 节点诊断复用正式重试/fallback 执行策略，并放宽异步 HTTP 等待窗口。
- 为 `/api/v2/workflow-versions/` 增加 Envoy 长请求规则，避免 15 秒默认路由超时。
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
- [x] CI/GitOps 与生产运行态验证

## 生产验收

- 源码 revision：`3513627f340b645ecb757035d7006aaca8cc1a6f`。
- GitHub Actions：run `30884853950` 全绿；Java/PostgreSQL、OpenAPI、Web、Python、系统 smoke、镜像扫描和签名全部通过。
- GitOps revision：`f7bb41cd40349c073a43fdd175810aa9226ff895`；ArgoCD `finbot` 为 `Synced/Healthy`。
- Backend、Web、Quant、Browser Worker 均为 `sha-3513627...`、单副本 `Ready`、零重启。
- Any2API 密钥来源为 `DATABASE_OVERRIDE`，Provider probe 为 `READY`，发现 42 个模型；仓库 Secret scan 未发现明文密钥。
- v10 为 `PUBLISHED/SDB_SCA_V1`：15 个研究 AI 节点使用 Any2API；确定性 `SOCIAL_CHOICE` 无 AI binding；两个最终执行节点保持 GPT-5.6 Sol/MAX 配置不变。
- 真实节点运行：LongCat、MiniMax、MiMo、GLM、DeepSeek 均得到成功结果；GLM 辩论席位最长一次约 131 秒。
- 最终公网 smoke：LongCat 清洗节点经 Cloudflare 在 57.8 秒返回 `COMPLETED` JSON，去除重复块并保留时间、数值和引用，未再触发旧 15 秒 504。

## 遗留外部风险

- Any2API 的 DeepSeek、LongCat、MiniMax usage 可能返回 `0/0`，MiMo 曾对短输入报告偏大的 prompt tokens；成本核算需要上游统一 usage 口径。
- Qwen 目录可发现但真实调用曾返回 502，MiniMax M2.7-highspeed 曾返回额度耗尽，继续排除在默认工作流之外。
- `/v1/models` 尚未携带模型级 `supported_parameters`；FinBot 已提供模型能力热配置，但上游若直接暴露该契约可进一步自动化探测。
