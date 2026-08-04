# TASK-20260804：全链路 SDB-SCA 决策面板

## 目标

按 ADR-036 将证据、研究、主审和执行统一到可恢复、双盲、对称、确定性终局的 Decision Panel Engine。

## 范围

- 多 panel session 领域模型、PostgreSQL 账本和确定性 ID。
- 提取通用 Panel Engine，并保持现有研究 SDB-SCA 行为等价。
- 新增主审与执行面板，交易计划继续经过硬风控与 OMS。
- 最后接入证据清洗/压缩互证、OpenAPI 与前端运行态。

## 非目标

- 不接入实盘资金交易。
- 不引入 Redis/MQ、多副本或单一 LLM 终局裁判。
- 不持久化或显示隐藏思维链。

## 验收

- 需求文档 39 的六项验收标准全部有自动化与生产 TestNet 证据。
- Java/Web/OpenAPI/PostgreSQL/Kustomize/Secret scan 全绿。
- Argo CD `Synced/Healthy`，默认工作流的四个面板均可观测、可恢复、失败关闭。

## 进度

- [x] 现状审计与边界设计
- [x] 需求与 ADR
- [x] P0a：多 panel session、强类型 identity、冻结输入哈希、复合唯一键和 CAS Store
- [ ] P0b：Panel Engine 提取、研究面板等价迁移与恢复回归
- [ ] P1：主审面板与约束投影
- [ ] P1：执行面板、硬风控和 OMS 联调
- [ ] P2：证据面板、OpenAPI 和 UI
- [ ] CI/GitOps 与生产 TestNet 全链路验收
