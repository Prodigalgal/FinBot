# AI Workflow v2 迁移与回滚

## 迁移原则

- 不批量改写现有 `workflow_versions` 和 `ai_sites.json`。
- v1 模板继续以 version 1 解析和执行；新内置模板使用 version 2。
- SQLite 只新增表和索引，不修改或删除既有 Council、decision、review 和 execution 数据。

## 配置迁移

- 新增运行配置默认 `autonomous.workflow_engine_version=2`、`autonomous.workflow_depth=standard`、`autonomous.workflow_director_enabled=true`、`autonomous.workflow_learning_enabled=true`。
- 显式选择 v1 模板时强制走 v1 适配器；workflow engine version 不改变模板内容。
- 旧 AI 配置通过运行时默认列表看到新内置模板，但只有用户保存/发布后才写入本地可变配置。

## 数据迁移

- `workflow_runs`：一次 Director/Workflow 执行根记录。
- `workflow_ledgers`：Task/Progress Ledger 快照。
- `workflow_node_checkpoints`：节点和迭代的幂等输出。
- `council_role_scores`：角色效果聚合。
- `council_memories`：带来源和标签的选择性记忆。

## 发布顺序

1. 发布 additive schema 和 v1/v2 parser。
2. 发布 v2 engine、Director 和 dry-run API，默认仍可回退 v1。
3. 发布五套模板与编辑器节点库。
4. 启用 learning，只读消费现有 outcome/review；不自动修改模板。
5. 完成 Worker restart/resume 和 live AI smoke 后再将标准模板设为默认。

## 本次实施结果

- 2026-07-12 已完成 additive schema、v1/v2 parser、v2 engine、Director、五套模板、learning、编辑器和运行面板。
- `autonomous.workflow_engine_version=2`、`autonomous.workflow_depth=standard`、`autonomous.workflow_director_enabled=true`、`autonomous.workflow_learning_enabled=true` 已进入运行配置和 Worker 配置快照。
- 标准模板已用 `sub2api / Responses / gpt-5.6-luna` 完成 1 轮真实 AI smoke：6 个角色和 1 个 Chair 全部成功；只使用临时数据库，未触发交易。
- 本地 Web/Worker 常驻任务已重启并验证；K8S 尚未在本次任务中发布，部署时继续使用现有单 Pod、共享 PVC、`replicas=1`、`Recreate` 约束。

## 回滚

- 将 `autonomous.workflow_engine_version` 设为 `1` 或指定 `product_advisory` v1 模板。
- 关闭 `autonomous.workflow_director_enabled` 和 `autonomous.workflow_learning_enabled`。
- 恢复旧 published Workflow version；新增表保留审计数据，无需删除。
- `paper_execution.submit_orders=false` 继续作为所有验证阶段的硬开关。
