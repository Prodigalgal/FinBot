# TASK-20260925：分析工作流聊天

## 目标

提供可保存、多轮继续的分析聊天。每条用户消息运行一个已发布的 FinBot 研究工作流，主对话展示确定性共识结论，运行详情展示各 AI 调用的正文输出、实际模型和状态。

## 范围

- 保存会话、原始用户消息、消息顺序、工作流版本和关联的 workflow run/task；同一会话一次只允许一条运行中的消息。
- 将最近已完成轮次的用户消息与共识结果作为有界上下文传入下一次运行，不修改原始用户消息。
- 复用现有工作流、持久任务、研究历史与 SSE；聊天任务明确标记为仅分析，执行共享证据和实盘研究分支，禁止创建模拟盘分支、交易决策与订单。
- 增加 `/api/v2/analysis-chats` 管理 API、OpenAPI 和手写 TypeScript 契约；增加与现有管理台风格一致的聊天页。
- 展示正文流与审计输出，不展示隐藏思维链；失败和未形成共识时明确显示状态，不伪造回答。

## 非目标

- 不触发任何模拟或实盘交易，不改变现有即时研究入口的行为。
- 不新增 AI Provider、工作流协议或外部消息系统。
- 本任务不执行生产发布或生产数据写入。

## 影响文件

- `services/backend/finbot-application`、`finbot-infrastructure`、`finbot-bootstrap`、新增 Liquibase changeset。
- `contracts/finbot-control-plane.openapi.yaml`、`apps/web/src`、相关直接回归测试。

## 验收标准与验证

- 创建和重开会话后可读取原始问题、工作流运行与最终回答；后续消息能引用之前的共识结果。
- 重复请求键不创建重复轮次；同一会话运行中拒绝并发发送；启动后尚未关联任务的轮次可用相同消息恢复。
- 聊天运行只进入研究分支；自动化测试证明不调用 demo branch、`TradeAutomationUseCase` 或 OMS。
- SSE 断线重连可去重/回放；各 AI 输出按 invocation 分开，完成后显示真实 Provider/Model 和失败原因。
- Java 相关测试、PostgreSQL/Liquibase、OpenAPI 契约、Web 测试与构建通过；若本机缺少依赖则如实记录未验证范围。

## 回滚

常规 FULL 任务继续使用旧 JSON 形状；新应用能读取旧任务。回退应用前先完成或取消分析聊天任务，旧 Worker 会拒绝包含 `ANALYSIS_ONLY` 的任务字段，避免误走交易路径。新增会话表为加法迁移，已发布 changeset 不回写。

## 发布记录（2026-09-25）

- 应用提交 `fbcf8e7dd1a7d26614bc3b8384216b5dc245e033`，CI Run `36149841568` 验证、四个 Core 镜像构建/扫描/签名及 GitOps 更新全部成功。
- GitOps revision `1564501cdb2257abe9df28a19e0b11fdd3e109d2` 已同步；Argo CD `Synced/Healthy`，Backend、Web、Quant、Browser Worker 均运行本次镜像，Pod 就绪且无重启。
- 生产 `finbot_v2` 已执行 `069-analysis-chat`，两张聊天表存在；首页与认证状态接口返回 200，未认证聊天列表返回 401。
- 仍待管理员会话完成创建、发送、逐 AI 输出和刷新恢复的生产端到端验收；发布核查没有发起聊天任务或交易。
