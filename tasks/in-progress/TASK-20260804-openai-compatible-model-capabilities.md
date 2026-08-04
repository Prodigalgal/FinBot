# TASK-20260804：OpenAI-Compatible 模型能力发现与发布校验

## 目标

在不绑定任何具体厂商的前提下，增强 FinBot 对 OpenAI Chat Completions / Responses
兼容端点的模型能力发现，并在工作流发布前拒绝确定性的 Provider、Model 与 Reasoning 配置错误。

## 范围

- 保留现有 `models: string[]` 标准目录结果，新增可选的通用模型能力描述。
- 按响应结构解析可用状态、协议、Reasoning、Token 参数、Streaming、Tools 和上下文上限。
- 为能力标注 `DECLARED`、`PROBED`、`MANUAL_OVERRIDE` 或 `UNKNOWN` 来源。
- 普通 `/models` 仅返回 `id` 时保持 `READY`，未知能力不阻止导入或发布。
- 工作流发布和回滚发布前校验 Provider/Model 存在、启用、归属和 Reasoning 上限。
- OpenAPI、手写 TypeScript 类型、设置页和契约测试同步更新。

## 非目标

- 不增加 Any2API、Sub2API 或其他厂商名称分支。
- 不在工作流发布事务内调用外部模型。
- 不在本阶段持久化短时运行健康状态，也不自动改写用户的模型配置。
- 不改变 Provider 当前显式选择单一 `CHAT` 或 `RESPONSES` 协议的设计。
- 不实现基于成本、延迟或健康度的自动模型路由。

## 影响文件

- `services/backend/finbot-application`：通用能力 DTO、发布校验。
- `services/backend/finbot-infrastructure`：`/models` 兼容解析及测试。
- `services/backend/finbot-bootstrap`：依赖装配。
- `contracts/finbot-control-plane.openapi.yaml`：增量响应契约。
- `apps/web`：能力类型、展示、导入限制和回归测试。

## 验收标准

- 标准 OpenAI `data[].id` 目录继续返回 `READY`，且能力为 `UNKNOWN`。
- 扩展目录可解析通用能力，未知或畸形扩展字段不会破坏模型名称发现。
- 明确 `UNAVAILABLE` 的模型不能在 UI 中选择导入；`UNKNOWN` 模型仍可导入。
- 发布与回滚发布会拒绝缺失/禁用 Provider、缺失/禁用/错属 Model 和 Reasoning 越界。
- 未知的可选能力不会阻止既有 Provider 和工作流发布。
- Java、OpenAPI、TypeScript、组件测试与生产构建全部通过。

## 测试方式

- Java 单元测试与 PostgreSQL 集成测试。
- OpenAPI 契约校验。
- Vitest 组件/API 测试与前端生产构建。
- Kustomize 渲染；发布后执行生产 Provider Probe 和工作流节点 smoke。

## 状态

- [x] 现状与契约审计
- [x] 通用能力 DTO 与目录解析
- [x] 工作流发布校验
- [x] OpenAPI 与前端
- [x] 本地全量验证
- [ ] CI/GitOps 与生产验收

## 已实现

- `ProviderModelCatalog` 保留 `models`，增量返回 `modelCapabilities` 与结构化 `warnings`。
- `OpenAiModelCatalogDecoder`、`OpenAiModelCapabilityParser`、`DiscoveredModelCapabilityMerger` 和独立告警收集器分别承担目录编排、单模型解析、重复保守合并和告警限界；支持标准 `data[].id`、兼容 `models[]`、协议分组参数和通用嵌套能力对象，最多返回 500 个模型。
- 能力模型不包含厂商名称，支持可用状态、协议、Reasoning、Token 参数、Streaming、Tools、多模态、Token 上限及逐字段来源。
- 工作流发布、回滚发布和重新激活前校验 Provider/Model 存在、启用、归属和 Reasoning 上限；发布事务不发起外部请求。
- 角色创建/更新复用相同的 Provider/Model/Reasoning 校验，非法绑定不会进入 Repository。
- 设置页展示能力状态与来源，不可用或协议不兼容模型不能导入，未知模型允许手工选择；导入值按单模型能力计算。
- Provider 连接参数变化会立即使旧目录失效，并以请求代次隔离迟到响应；角色和最终执行编辑器只允许启用且归属一致的 Provider/Model/Reasoning 组合。
- 工作流编辑器不再生成跨 Provider 的 Model 绑定，并排除禁用 Provider/Model。
- Provider 使用表单中新 URL/Key 读取目录时不会回退测试数据库旧配置。

## 本地验证

- Java：JDK `26.0.1` 下 `gradlew test --rerun-tasks --no-configuration-cache` 通过。
- 前端：12 个测试文件、29 个测试通过；TypeScript/Vite 生产构建通过。
- 契约：控制面 93 paths / 109 controller operations 校验通过。
- 工程：Secret scan 与生产 Kustomize render 通过，`git diff --check` 通过。
