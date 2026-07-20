# TASK-20260715 TradFi/CFD 预估交易

状态：Done。`023-estimated-trading` 已在生产执行，领域计算、永久记录、API 和交易面板均保留在当前 Java 主系统。

## 目标

- 对 `execution_enabled=false` 但仍可研究的产品生成可审计的预估交易，展示数量、入场、止盈、止损、杠杆、保证金及预估净盈利/亏损。
- 保持可执行产品现有 TestNet/Demo OMS 路径不变，不把预估记录伪装成交易所订单。

## 范围

- 新增确定性预估交易领域计算与边界测试。
- 新增 PostgreSQL 永久预估记录、自动化状态、查询 API 详情和交易面板展示。
- 仅在方向性 `BUY/SELL` 决策没有可执行映射、但存在仅研究映射和最新市场价时生成预估。

## 非目标

- 不扩展产品/交易所模型，不模拟交易所成交、滑点撮合、资金曲线或 CFD 经纪商行为。
- 不为 `WATCH/HOLD` 创建预估单，不调用任何交易所私有下单 API。
- 本期不自动跟踪预估单命中止盈/止损；记录作为不可变的决策时点测算事实。

## 影响文件

- `services/backend/finbot-domain`：预估输入、结果与计算引擎。
- `services/backend/finbot-application`：自动化分流、持久化端口与查询 DTO。
- `services/backend/finbot-infrastructure`：JDBC 实现、Liquibase 迁移和集成测试。
- `apps/web`：预估状态、数量和盈亏详情。

## 验收标准

- 数量同时受风险预算、最大名义价值、最小数量和数量步长约束。
- 净盈利/亏损包含双边 taker fee 与 slippage；杠杆只用于计算初始保证金。
- 无可执行映射时，符合条件的研究产品落一条 `estimated_trade_projection`，自动化状态为 `ESTIMATED`，且 `oms_order` 数量保持为零。
- 无最新价格或方向/价格关系不合法时保持 `BLOCKED`，错误原因可读、可查询。
- Java 单元/集成测试、前端 build、HTTP 和浏览器 smoke、K8S 运行态验证通过。

## 测试方式

- `gradlew test build`
- PostgreSQL Liquibase fresh/upgrade integration test
- `npm run build` 与生产浏览器 smoke
- Argo CD 同步、Pod、数据库与公开 API 验证
