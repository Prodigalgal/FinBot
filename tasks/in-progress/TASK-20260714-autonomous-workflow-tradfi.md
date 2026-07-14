# TASK-20260714 自主工作流、TradFi 与模型兜底

- [x] P0：完成上一轮 provider timeout/budget/自动恢复的生产闭环验证。
- [x] P0：实现节点级自由主模型/兜底模型绑定及完整重试顺序。
- [x] P0：发布宽预算、长超时、全阶段内置主工作流新版本。
- [x] P1：拆分期望杠杆、风控上限和交易所最大杠杆，移除 100x 任意限制。
- [x] P1：新增 X 信息源并复用 Firecrawl IPv4 代理 fail-closed 路由。
- [x] P1：以现有 `LINEAR_PERPETUAL` 映射 Bybit Demo 可验证 TradFi Perpetual；放弃 Gate/MT5 CFD 测试网增强，不扩展产品模型。
- [x] P1：补齐交易所账户启停和产品映射执行门禁；不可模拟交易的 Bybit TradFi 默认仅研究。
- [x] P1：完成 Binance Spot/USDM/COINM Demo 公共目录调查；发现 TradFi Perpetual，等待独立 Demo Futures key 后做私有开平仓验收。
- [x] P1：支持多个工作流定义同时激活与独立调度。
- [x] P2：完成工作流、设置、产品中心和运行详情 UI/UX 收口。
- [ ] P2：完成 Java/Python/前端、Liquibase、HTTP、浏览器和 K8S 生产验收。
- [x] P2：更新 README、架构图、ADR、调查报告与回滚边界。

## 当前外部边界

- Bybit Demo 的 TradFi Perpetual 私有下单受账户协议/资格限制，默认仅研究。
- Binance USDⓈ-M Demo 已发现 TradFi Perpetual，等待独立 Demo Futures key 后再做私有开平仓验收。
