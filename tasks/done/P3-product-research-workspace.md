# P3 产品研究工作台专案

## 当前阶段：P3.1-P3.4 与 P3.1B 首次双交易所模拟写入已完成

> P0 数据可信度、运行恢复以及 P3.2-P3.4 的统一推进已由 `tasks/done/P0-P1-quality-closure.md` 完成；本文件保留产品工作台里程碑状态。

- [x] 审计现有产品目录、场所合约、Hybrid Universe、API 和 Web UI。
- [x] 固化需求边界与产品/Watchlist/Universe 架构决策。
- [x] 新增 Watchlist schema、查询与领域校验。
- [x] 新增产品分页/详情和 Watchlist CRUD API。
- [x] 将 `research` / `pinned` 关注项接入 Hybrid Universe。
- [x] 接入 Gate USDT 永续与 Bybit Linear Mainnet 公共目录及行情；实时目录已包含 Gate 3032、Bybit 722 个活跃产品。
- [x] 新增 Gate TestNet / Bybit Demo 并发模拟执行、幂等审计与独立门禁。
- [x] 实现桌面与移动产品中心 UI。
- [x] 展示模拟交易 readiness 和最近订单。
- [x] 完成单元测试、构建、HTTP 与浏览器 smoke。
- [x] 将 Gate TestNet 过期 host 切换到当前官方基址，并用现有凭据通过只读探针。
- [x] 配置 Bybit 可用的订阅优先节点标签，并通过 Demo 标准只读探针。
- [x] 两家只读探针通过后执行一次每家最多 1 单、单笔最多 100 USDT 的受控并发模拟下单。

运行证据：执行 run `c3957c42717a374de81ecf53d1221c55` 为 `passed`；Gate TestNet `filled`、Bybit Demo `submitted` 且后续持仓查询确认成交，`submit_orders` 已恢复关闭。

## 后续阶段

- [x] P3.2 历史、对比、重放、续跑和人工复核。
- [x] P3.3 工作流版本、发布、回滚和节点测试。
- [x] P3.4 Shadow Portfolio、回测、校准、受控反思和通知。

## 验收入口

- 需求：`docs/requirements/22-p3-product-research-workspace.md`
- 决策：`docs/decisions/005-p3-product-catalog-watchlist-universe.md`
- 模拟执行：`docs/requirements/23-p31-multi-exchange-paper-execution.md`、`docs/decisions/006-multi-exchange-paper-execution.md`
- 验证：`python -m unittest discover -s tests -v`、`npm run build`、API/浏览器 smoke。
