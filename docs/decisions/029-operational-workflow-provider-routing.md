# ADR 029：默认研究工作流的可用 Provider 路由

状态：Accepted，2026-07-20。

## 背景

生产真实双环境 smoke 证明分段、共享证据和分支隔离正确，但默认工作流 v6 的清洗、压缩和部分分析节点主/兜底都依赖 `provider_gemini_default` 或 `provider_mimo_default`。当前运行态这两个 Provider 分别返回 `HTTP_503` 和 `PROVIDER_ERROR`，导致证据虽然可以完成，两个研究分支无法进入成功终态。

## 决策

新增不可变 `workflowversion_standard_v7`，保留 v6 供历史运行查询和回滚使用，并将 v7 发布为默认版本：

- 证据清洗、信息压缩、证据分析、看多/看空、市场结构和风险席位使用 `provider_grok_sub2api` 与 `provider_sub2api_default` 混合编排。
- Grok 席位使用 `grok-4.5` + `XHIGH`；GPT 席位使用 `gpt-5.6-terra` + `XHIGH`。
- 每个清洗/压缩/分析席位仍保留不同 Provider 的兜底，不把 Provider 或模型硬编码进领域类型。
- Gemini/MiMo 保留为可由用户在工作流编辑器中选择的 Provider，但不再作为内置主工作流的默认依赖。
- v7 不改变信息证据、产品、交易所、账户或模拟环境的数据隔离契约。

## 取舍

该决策优先保证生产工作流的可运行性和多模型混杂。Sub2API 是统一网关，但 Grok 与 GPT 仍是不同模型家族；后续若 Gemini/MiMo 恢复，可通过用户工作流版本或新的内置版本重新加入默认席位。

## 回滚

通过工作流控制面将 `workflow_standard_product_research` 回滚到 `workflowversion_standard_v6`。v6 不删除，已有历史运行不受影响。数据库 changeset 的 rollback 只归档 v7 并恢复 v6 发布状态，不删除被历史运行引用的工作流版本。

## 验收

- Liquibase offline validation 和 PostgreSQL integration test 必须验证 v7 发布、v6 归档、节点/边完整复制及默认绑定不含 Gemini/MiMo。
- Provider probe 必须对 Grok 和 Sub2API 返回 `READY`。
- 生产重新执行 `EVIDENCE -> LIVE_RESEARCH / DEMO_AUTOTRADE`，两分支需分别落库并保持相同 evidence artifact。
