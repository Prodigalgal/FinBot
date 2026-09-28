# 模拟盘标的与并发占额边界

## 目标

模拟交易只消费已经持久化的单产品研究范围。AI 决策、执行候选、账户环境和最终订单必须属于该范围；同一账户的并发订单规划需要共享仓位名额。

## 范围

- 未形成 `research_market_scope` 的工作流继续生成研究报告，但模拟交易以 `BLOCKED` 结束。
- 决策 symbol 与研究范围不一致时保存阻断原因，不生成交易提案或订单。
- 只查找研究范围内的 `instrument_id`、exchange、symbol、K 线周期和模拟 environment；行情须在执行检查前 15 分钟内观测，K 线开盘时间不得早于两个周期，未来时间戳不参与执行。若匹配多个启用账户，阻断并要求管理员先明确账户配置。
- 在保存已批准意图和订单的同一数据库事务内锁定目标账户，检查当前持仓、未终结订单及尚未由后续归零仓位快照确认的成交占用的 symbol；同一 symbol 不重复开单，账户已达 `maximum_open_positions` 时阻断。`FILLED` 本身不会自动变成 `RECONCILED`，所以成交占额只能在成交时间之后出现该 symbol 的 `FLAT` 仓位快照时释放。
- 已持久化订单但自动化状态仍为 `STARTED`/`FAILED`，或仍为 `ORDER_PLANNED` 而 OMS 已有终态时，根据 OMS 状态恢复为 `ORDER_PLANNED`、`SUBMITTED` 或 `BLOCKED`；只有仍需提交的订单复用现有幂等提交逻辑。
- 不改变 `ANALYSIS_ONLY` 聊天边界，也不在本次加入自动发现或真实盘执行。

## 影响文件

`TradeAutomationApplicationService`、`TradeAutomationStore`、`JdbcTradeAutomationStore` 和相关测试。现有数据库表与 API 不变。

## 验收与验证

- 无范围、symbol 不一致、多账户、重复 symbol、仓位名额用尽均无新订单，返回可追踪的阻断原因。
- 合法单账户决策只生成一张属于研究范围的模拟订单；重试仍遵守现有幂等键。
- 并发为同一账户规划时，账户锁使第二个事务看见第一个已规划订单。
- 崩溃或异常后已持久化的订单可恢复；已被交易所接受的订单不重复提交，也不重新调用 AI 决策。
- 运行相关 Gradle 单元测试、基础设施测试及构建；有 PostgreSQL 测试环境时验证 SQL 与并发行为。

## 兼容与回滚

本次对无范围的历史工作流实施安全阻断，历史研究与报告仍可读。回滚应用代码即可恢复旧的模拟交易选择逻辑；数据库无迁移。

## 验证记录

- 本机使用 JDK 26 运行 `gradlew test :finbot-bootstrap:bootJar :finbot-migration:bootJar --max-workers=1`，构建通过；新增应用层测试覆盖范围拒绝、单账户规划、重复 symbol、订单恢复。
- 本机无 PostgreSQL 测试服务，`LiquibasePostgresIntegrationTest` 的 27 个用例均被跳过；新增 SQL 范围查询、订单占额、`FLAT` 释放和状态恢复用例已编译，仍须在带 PostgreSQL 的 CI 中执行。
- 一次并行 `clean test` 中，未修改的 `AiCompletionCollectorTest` 因一秒内未观察到取消事件而失败；单测复跑和单 worker 全套测试通过。该时序波动不归入本次模拟盘改动。
