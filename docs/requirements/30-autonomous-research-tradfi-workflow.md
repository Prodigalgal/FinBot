# S3 自主研究、TradFi 与可恢复工作流增强

> 状态：已实现。016-022 migration、节点级模型兜底、可调杠杆、多工作流、交易所/产品门禁和仅研究 TradFi 边界均已进入生产；真实 TradFi 账户资格不在本系统承诺范围内。

## 目标

把当前“采集 -> 清理/压缩 -> 量化 -> 多 Agent 辩论 -> 主席裁决 -> 模拟交易机器人”闭环增强为可长期运行、可自由编排、可降级恢复的主工作流，并增加 X 与 Bybit Demo 可验证产品的覆盖。

## 范围

- 信息源增加 X，复用 Firecrawl IPv4 强制代理路由；没有可用代理时 fail closed。
- 产品库继续使用 `canonical_product -> venue_instrument`，不新增平行产品模型；Bybit 的股票、贵金属 `TradFi Perpetual` 按既有 `LINEAR_PERPETUAL` 合约映射。
- 交易所账户提供可审计、带版本冲突保护的启停开关；停用后行情候选、账户同步、风控候选与新订单统一停止使用该交易所。
- 合约映射区分“可研究”和“可自动执行”；无法完成 Demo 开仓/平仓闭环的产品必须默认仅研究。
- 每个 LLM 工作流节点拥有一组主模型绑定和零或一组兜底模型绑定；两组绑定均由用户自由选择厂商、模型和思考强度。
- 主模型完成节点自身重试后才切换兜底模型；兜底模型也完成自身重试后仍失败，节点才按工作流失败策略终止、继续或请求重规划。
- Gemini 3.5 Flash 只作为可选 provider/model 数据，不成为硬编码兜底或强制依赖。
- 节点超时、重试次数、退避、单节点输出上限，以及工作流 token、费用和总时长预算均可配置；内置主工作流采用适合深度推理的宽松默认值。
- 系统提供一个最大覆盖的内置主工作流；用户可继续新建、复制、编辑和发布其他工作流。
- 允许多个工作流定义同时激活；一次研究按明确的 workflow definition/version 执行，自动调度按激活配置分别创建运行，互不复用状态。
- 杠杆配置区分期望杠杆、系统风控上限和交易所合约 `maximum_leverage`。交易所字段只作为硬上限，不代表每次实际使用最大杠杆。
- 移除任意的 100x 业务限制；实际杠杆由用户期望值、风险模型、风控上限和交易所硬上限共同约束。

## 非目标

- 不开放真实盘交易；Gate TestNet 与 Bybit Demo 仍是唯一自动执行目标。
- 不绕过交易所真实合约上限、最小数量、价格步长、保证金或区域限制。
- 不把模型内部 chain-of-thought 原文展示给用户；只保存和展示可审计的结论、依据、反思摘要、调用状态和成本。
- 不把 X 登录态、cookie 或用户私有内容纳入采集。
- 不猜测交易所未公开或不可验证的 TradFi 产品。
- 不实现 Gate TradFi、Bybit MT5 或其他无法在官方测试环境验证下单的 CFD；后续只有在官方 API 和测试账户均可闭环时再独立立项。

## 影响模块

- 工作流领域与执行：`finbot-domain/workflow`、`finbot-application/workflow`、`finbot-application/ai`。
- 工作流与 AI 持久化：`finbot-infrastructure/workflow`、Liquibase changesets。
- 产品与采集：`finbot-domain/catalog`、`finbot-application/catalog`、`finbot-infrastructure/ingestion`、交易所 public adapters。
- 交易风控：`finbot-domain/risk`、交易配置 API、JDBC repositories。
- 前端：工作流编辑器、设置、产品中心、即时研究与运行详情。
- 部署：provider secret/env 模板、K8S 单副本工作负载和 Argo CD 配置。

## 验收标准

- 工作流编辑器可以独立保存主绑定与兜底绑定，任一厂商/模型均可被选作兜底，未配置兜底时保持现有主模型失败语义。
- 自动化测试证明调用顺序是 `primary retries -> fallback retries -> node failure`，且审计记录能区分实际调用的 provider/model/reasoning。
- 最大主工作流能完整执行多阶段采集、量化、三轮辩论、主席裁决、执行草案、反思与模拟执行，预算不足不会被固定的低额上限误伤。
- 两个不同工作流可同时处于激活状态，并由一次调度周期各自产生独立 run；手动/即时研究可明确指定工作流。
- X 信息源通过 Firecrawl 代理路由执行并保留 source/provenance；代理不可用时不直连。
- Bybit Demo 目录可显示股票和贵金属线性永续，并继续复用既有合约元数据；不为不可交易 CFD 增加新枚举或表。
- 产品详情能明确显示“可模拟执行”或“仅研究”，交易账户面板可启停 Gate/Bybit，状态刷新后后台行为同步生效。
- 500x 合约可保存 `maximum_leverage=500`；用户可选择较低实际杠杆，最终杠杆永远不超过交易所与风控边界。
- Java 全量测试、Python 类型/测试、前端 build、Liquibase 空库升级、API smoke 和桌面/移动浏览器 smoke 通过。
- K8S 保持每个业务组件单副本，Argo CD `Synced/Healthy`，数据库变更完成且 Pod 无新增重启。

## 测试方式

```powershell
$env:JAVA_HOME='D:\DevlopEnv\JDK\jdk-26.0.1'
services\backend\gradlew.bat test
python -m pytest services\quant\tests
Set-Location apps\web
npm run build
```

- Testcontainers：Liquibase、fallback 调用顺序、多工作流激活、杠杆约束与目录 upsert。
- HTTP/API：工作流 CRUD/激活、配置快照、产品目录、研究触发、SSE 终态。
- 浏览器：工作流绑定编辑、设置表单、产品筛选、即时运行详情、窄屏无溢出。
