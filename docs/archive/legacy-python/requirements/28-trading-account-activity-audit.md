# S2 交易账户操作审计与真实历史

## 目标

在现有 Gate TestNet / Bybit Demo 账户页中提供可筛选、可追溯的真实操作历史，串联 AI 决策、执行门禁、本地 OMS、交易所订单与成交事实，并明确每个数据源的可用性和完整性。

## 范围

- 新增统一的交易活动只读模型与聚合服务，合并本地 `paper_executions`、`oms_orders` / `oms_order_events` 和交易所只读订单、成交、账户流水与平仓盈亏历史。
- 新增 `GET /api/v1/exchange-account-activity`，支持交易所、事件层级、状态、合约、起止时间、分页上限筛选。
- Gate TestNet 与 Bybit Demo 客户端增加只读历史查询；继续固定模拟环境 host，并复用独立交易所代理策略。
- 账户页增加“账户概览 / 操作历史”视图；历史列表展示发生时间、来源、阶段、交易所、合约、方向、数量、价格、状态、关联 ID 和可展开详情。
- API 和界面明确展示本地记录、交易所记录、查询截断、代理/鉴权失败及“当前确实无记录”等真实状态。

## 非目标

- 不接入 Gate / Bybit Mainnet 私有 API，不新增真实交易、撤单、资金划转或账户设置写操作。
- 不把本地 `planned` / `submitted` 误报为交易所已成交；不使用示例、随机或推导交易记录填充空态。
- 不保存未经筛选的交易所原始响应，不返回 API key、secret、签名、鉴权 header 或代理凭据。
- 本期不新增数据库表；历史视图读取既有审计表与交易所只读接口。

## 影响文件

- `finbot/exchange/account_activity.py`
- `finbot/exchange/gate_testnet.py`、`finbot/exchange/bybit_demo.py`、`finbot/exchange/runtime.py`
- `finbot/web/service.py`
- `web-ui/src/ExchangeAccountsPanel.tsx`、`web-ui/src/api.ts`、`web-ui/src/types.ts`
- `tests/test_exchange_account_activity.py`、既有交易所账户测试

## 验收标准

- 本地审计与交易所事实使用不同 `source_type`，状态与时间戳不相互覆盖；相同订单可通过 `client_order_id` / `exchange_order_id` 关联。
- Gate / Bybit 任一历史源失败时返回 `partial` 和脱敏错误，另一交易所与本地历史仍可读。
- 时间、交易所、层级、状态、合约筛选生效；返回数量受上限约束并显式报告 `complete` / `truncated`。
- 详情只包含白名单字段；响应文本中不出现凭据、签名或代理连接信息。
- 无执行、无 OMS、无交易所订单时如实显示空态，并能解释各数据源是否已成功查询。
- 桌面和移动端均可完成筛选、刷新、展开详情；无页面级横向溢出和控制台错误。

## 测试方式

```powershell
python -m unittest tests.test_exchange_account_activity tests.test_exchange_accounts -v
python -m compileall -q finbot
python scripts/secret_scan.py
cd web-ui
npm run build
```

生产候选还需完成带认证的 HTTP smoke、Gate TestNet / Bybit Demo 真实只读历史探针，以及 ArgoCD / Pod / API / 浏览器运行态验收。
