# FinBot 需求状态索引

> 本索引以当前代码、Liquibase 和生产运行态判定状态。已实现需求保留原文作为契约与迁移审计，不再作为待办；真正待推进的范围只列在“进行中”。

## 进行中

| 需求 | 当前状态 | 未闭环事项 |
| --- | --- | --- |
| [`32-segmented-dual-environment-research.md`](./32-segmented-dual-environment-research.md) | 代码与 030 migration 已上线 | 缺少当前生产基线下真实 `EVIDENCE -> LIVE_RESEARCH / DEMO_AUTOTRADE` 双分支 smoke |
| [`37-crawler-challenge-runtime.md`](./37-crawler-challenge-runtime.md) | Browser Worker 代理隔离、容量门禁和直接测试已完成；C2/C3 未启用 | 受控生产 solve、cookie 回放、证据落库与关闭回滚 |

## 已实现

| 需求 | 主要代码/数据证据 |
| --- | --- |
| [`29-java-python-breaking-architecture.md`](./29-java-python-breaking-architecture.md) | Java 26 主系统、Python Quant HTTP/SSE、PostgreSQL `finbot_v2`，旧 SQLite/Web Worker 已退出生产 |
| [`30-autonomous-research-tradfi-workflow.md`](./30-autonomous-research-tradfi-workflow.md) | 016-022 migration、节点主/兜底模型、多工作流、杠杆语义与执行隔离 |
| [`31-feature-parity-migration.md`](./31-feature-parity-migration.md) | React 13 工作区、`/api/v2`、CSRF、SSE、研究/交易/配置页面和生产浏览器 smoke |
| [`33-audit-p1-p3-hardening.md`](./33-audit-p1-p3-hardening.md) | Worker 背压、行情完整性、OpenAPI、关键交易恢复与 Web 测试门禁 |
| [`34-multi-agent-evidence-consensus.md`](./34-multi-agent-evidence-consensus.md) | 031 migration、`AI_CLEANER`、多 `COMPRESSOR`、`COMPRESSION_VALIDATOR` 与审计存储 |
| [`35-runtime-configuration-control-plane.md`](./35-runtime-configuration-control-plane.md) | 032-034 migration、资源无关 Secret、Provider/Model/来源/代理热配置与测活 |
| [`36-dual-kernel-proxy-gateway.md`](./36-dual-kernel-proxy-gateway.md) | sing-box/Xray 双内核镜像、051 migration、UI 热切换和生产双内核并行运行 |
| [`2026-07-18-first-party-crawling-architecture.md`](./2026-07-18-first-party-crawling-architecture.md) | 035-043 migration、统一 transport、协议采集、SSRF/重定向/背压与证据块 |
| [`2026-07-18-multi-domain-source-catalog-v3.md`](./2026-07-18-multi-domain-source-catalog-v3.md) | 046-049 migration，生产目录 v4 共 62 个来源 |
| [`2026-07-19-public-searxng-instance-pool.md`](./2026-07-19-public-searxng-instance-pool.md) | 独立公共池 provider、真实代理调用、冷却和稳定错误分类 |

## 历史版本

- [`2026-07-18-default-source-catalog-v1.md`](./2026-07-18-default-source-catalog-v1.md) 与 [`v2`](./2026-07-18-default-source-catalog-v2.md) 是 append-only manifest 的历史边界，只用于审计和回滚。
- 旧需求正文不因完成而删除；新增行为通过后续 requirement/ADR 修订，已发布 Liquibase changeset 永不回写。

## 状态判定规则

- “代码存在”只能证明实现能力。
- “已上线”需要 migration、CI、GitOps 与 Pod/接口证据。
- “已启用”还需要生产配置和真实调用记录；例如 Browser Worker 已上线，但当前 62 个来源均未启用 C2/C3。
