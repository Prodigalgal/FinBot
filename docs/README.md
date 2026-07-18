# FinBot 文档索引

文档按用途组织，代码和运行态是实现事实，需求与 ADR 是边界事实。历史 Python 阶段材料不再作为当前命令或目录结构的依据。

## 当前文档

| 目录 | 内容 | 入口 |
| --- | --- | --- |
| `requirements/` | 业务边界、验收标准和跨模块需求 | [`2026-07-18-multi-domain-source-catalog-v3.md`](./requirements/2026-07-18-multi-domain-source-catalog-v3.md) |
| `decisions/` | 已接受的架构决策与取舍 | [`024-multi-domain-news-search-discovery.md`](./decisions/024-multi-domain-news-search-discovery.md) |
| `migrations/` | 数据与运行时迁移计划、门禁和回滚 | [`010-java-breaking-exec-plan.md`](./migrations/010-java-breaking-exec-plan.md) |
| `reports/` | 验收证据、审计和运行态结论 | [`30-java-breaking-migration-acceptance.md`](./reports/30-java-breaking-migration-acceptance.md) |
| `archive/legacy-python/` | Java breaking migration 前的阶段设计 | [`README.md`](./archive/legacy-python/README.md) |

## 维护规则

- 新的业务约束写入 `requirements/`，长期技术取舍写入 `decisions/`。
- 数据、API、部署或运行时 breaking change 必须在 `migrations/` 给出门禁和回滚边界。
- 只有真实测试、CI、数据库或 K8S 证据才能写入 `reports/`；计划值不得伪装为验收结果。
- 已发布 Liquibase changeset 不修改，只追加。
- 目录和命令统一以根 [`README.md`](../README.md) 为准；旧 Python 命令只允许出现在归档材料中。
- 任务状态维护在 [`tasks/current.md`](../tasks/current.md) 与 `tasks/in-progress/`，不把任务流水账堆回文档入口。

## 运行手册

- K8S / Argo CD：[`platform/k8s/README.md`](../platform/k8s/README.md)
- Java Backend：[`services/backend/README.md`](../services/backend/README.md)
- Quant：[`services/quant/README.md`](../services/quant/README.md)
- Proxy Gateway：[`services/proxy-gateway/README.md`](../services/proxy-gateway/README.md)
- Web：[`apps/web/README.md`](../apps/web/README.md)
