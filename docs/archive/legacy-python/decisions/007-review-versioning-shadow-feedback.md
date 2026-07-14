# ADR-007：复核门禁、不可变工作流版本与本地反馈闭环

## 状态

已接受，2026-07-11。

## 背景

FinBot 已能生成多 Agent 决策、评估报告和模拟执行计划，但“最新结果文件”、可变 JSON 工作流配置和自动循环内执行步骤不足以支撑人工复核、历史重放、版本回滚和长期校准。尤其在启用模拟提交后，仅依赖 `candidate + confidence` 会让人机责任边界不清晰。

## 决策

- `ai_trade_decisions` 保持不可变事实；新增 `decision_reviews` 保存本地人工判定和乐观锁版本。
- 模拟执行默认强制 `approved`，并在执行前重新读取 decision、review、loop readiness、Portfolio Risk 与 AI Governance；客户端不能提交订单参数。
- 历史以现有 loop/pipeline/debate/decision 表做聚合查询；replay 创建新 request 并记录来源，不复制或修改旧结果。
- Council template 的编辑态继续复用现有结构，新增 SQLite `workflow_versions` 作为 draft/published 历史；发布成功后才同步当前 JSON 配置。
- 回滚通过复制旧快照生成新版本，保留单调版本号与完整审计链。
- Shadow Portfolio 与回测使用已存决策、candle 和 outcome 做 point-in-time 计算；它们是研究反馈，不是交易所账户事实。
- 通知首版采用本地 Inbox 和幂等 dedupe key，不引入外部 SaaS 写入或新的密钥边界。
- 新闻/事件研究只接受明确的确认状态；Gate/Bybit 等至少两家公开行情可在严格方向、强度与价差阈值下生成独立的 `market-confirmed`。即使关联新闻仍待补证，也只允许按 `confirmation_scope=market-only` 进入技术面人工复核，并保留基本面未确认风险，不伪装成基本面确认。
- 云端运行采用 K8S 单 Pod 双容器，共享持久化运行根目录；`FINBOT_RUNTIME_ROOT` 将热更新配置和 AI Workflow 从只读镜像中分离。

## 取舍

- 人工复核会让新决策无法在同一自动循环里立即下模拟单，但换来明确授权、审计和可恢复性；批准后由独立 API 执行。
- SQLite 聚合适合当前本地单用户规模；查询保持分页和索引。并发或历史量超过既定阈值后再评估 PostgreSQL/CQRS 读模型。
- 工作流快照存在少量 JSON 重复，但避免节点、角色和 Prompt 被跨版本引用后漂移。
- 站内通知不具备即时外部触达，但可先验证规则、去重与用户操作动线，且没有外部副作用。
- SQLite 让首版部署简单，但阻止 Web/Worker 拆成多 Pod 横向扩容；迁移 PostgreSQL 前保持一个 Pod、一个 PVC 和 `Recreate` 更新。

## 兼容与安全

- 全部 schema additive；既有 decision 没有 review 时映射为 `pending`。
- 旧 Council template 首次进入版本页时可物化为 version 1 draft，不改变当前运行配置。
- 真实盘 host、私有 Mainnet API 和 AI 自定义订单参数继续由执行 adapter 硬拒绝。
- `submit_orders` 与人工批准是相互独立的双重门禁，任一未满足都不得发送模拟写请求。
- `needs-followup` 即使存在关联研究条目也不满足执行门禁；跨交易所行情互证若方向冲突、provider 少于 2、最低强度低于 0.60 或价差超过 1%，同样不满足。
