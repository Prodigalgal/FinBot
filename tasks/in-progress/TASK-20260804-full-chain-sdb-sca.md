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
- [x] P0a：多 panel session、强类型 identity、冻结输入 JSON/哈希、重试恢复、复合唯一键和 CAS Store
- [x] P0b：Panel Engine 提取、研究面板等价迁移与恢复回归
- [x] P1a：主审面板独立审计（PRINCIPAL_REVIEW）、反例检验与参数单调收紧（置信度截断/止损收紧/杠杆约束）
- [x] P1b：执行面板（EXECUTION）、硬风控联动与高真实感模拟撮合（非线性滑点冲击、资金费率计提、限价穿越）
- [x] P2：全链路多面板协议轨迹投影、OpenAPI 契约一致性与前端 UI/UX 运行态全面升级（多面板 Tabs 切换、主审独立审计卡片、SDB-SCA 执行社会选择可视化）
- [x] CI/GitOps 与生产 TestNet 全链路验收（GitHub Actions 全绿、Docker 镜像签名推送、Argo CD 生产集群自动化同步上线）
