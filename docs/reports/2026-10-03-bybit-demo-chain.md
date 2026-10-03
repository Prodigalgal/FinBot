# Bybit Demo 账户链路修复

## 目标与边界

按已批准的模拟盘范围，打通当前 Bybit Demo 的行情、账户事实同步与订单对账读取。实盘关闭；不切换账户模式，不绕过共识和硬风控创建订单。当前 API 和数据库 schema 不变。

## 当前证据

2026-10-03 18:40（Asia/Shanghai）核对：`account_bybit_demo_default` 启用，环境为 DEMO，`/v5/account/info` 返回 `marginMode=ISOLATED_MARGIN`、`unifiedMarginStatus=3`。钱包、持仓、历史订单、成交、已平仓盈亏和 BTCUSDT 合约/行情的认证或公共读取均返回成功；一次本机开放订单诊断出现时间窗口错误，修正诊断脚本的服务器时钟采样边界后，18:51 针对该 GET 复测返回 `retCode=0`、0 张订单。没有调整生产服务器时钟或账户配置。

旧后端近一小时 ACCOUNT_SYNC 成功 60 次、失败 30 次；失败均属于 Bybit，错误 `Exchange account response is missing a required balance`。ORDER_RECONCILIATION 完成 120 次，但 OMS 无订单，该结果不足以证明真实订单生命周期。Bybit 同步错误导致新的账户快照和同步游标无法生成。

## 根因与修复

[Bybit 钱包官方契约](https://bybit-exchange.github.io/docs/v5/account/wallet-balance)规定：账户级字段不适用于逐仓；UNIFIED 的 `availableToWithdraw` 已废弃。当前账户两项可用余额字段为空，旧代码仍将其作为必填。

- `JdkExchangeAccountGateway` 增加 UNIFIED 账户模式读取，在拉取历史记录前校验余额，避免失败时继续无效抓取。
- `BybitUsdtBalanceMapper` 按模式映射。逐仓使用 USDT `walletBalance - spotBorrow - totalPositionIM - totalOrderIM - locked - bonus`，负可用余额按 0 保存；保留有符号浮动盈亏。
- cross/portfolio 使用账户级美元数据，经 USDT `usdValue/equity` 换算为现有账本的 USDT 单位，避免直接把 USD 标成 USDT。账户仍有价值而无法获得换算价格时明确失败。
- UNIFIED 不读取废弃的可提现字段。CONTRACT 路径保留已有字段回退，Gate 路径不变。
- 必填字段缺失、非法数值、负保证金和未知模式明确失败，不把缺数据填成可用资金。
- `JdkPaperExchangeGateway` 修复另一处提交阻塞：设置杠杆返回 `110043` 表示目标杠杆已经生效，将其作为幂等成功继续处理；HTTP 异常、认证/权限失败、风险限额和其他杠杆拒绝仍阻断。该语义来自 [Bybit 官方错误码](https://bybit-exchange.github.io/docs/v5/error)。

Demo 域名为 `https://api-demo.bybit.com`，沿用既有签名、出站路由、重试和账本幂等。[Demo 官方说明](https://bybit-exchange.github.io/docs/v5/demo)确认账户、持仓和订单相关 REST API 的适用范围。

## 验证与运行状态

新增 11 个余额映射测试和 2 个杠杆响应测试，覆盖逐仓字段缺失、扣借款、零资金/亏损、废弃字段、USD/USDT 换算、空账户、缺价格、非法保证金、未知模式、CONTRACT 兼容及杠杆幂等/拒绝边界。本机首轮全后端测试 418 项：390 项通过，28 项数据库集成测试跳过，失败 0；bootJar 构建通过。

首次 CI `37117921679` 的 27 项 PostgreSQL 集成测试通过，发布被 `AiCompletionCollectorTest` 取消订阅断言阻断。竞态发生在注册取消回调后：等待线程先观察到取消、抛异常并移除回调，取消线程便可能无法执行订阅关闭。`AiCompletionCollector.await` 现在在取消异常分支主动关闭流，再释放注册；取消测试重复 20 次覆盖线程交错。后续 CI 和生产账户同步结果待最终验收回写。

第二次 CI `37118206180` 的后端 437 项测试（含 27 项 PostgreSQL 集成测试）全部通过，发布被前端依赖安全门禁阻断。`apps/web/package.json` 和 lockfile 将 Vitest 系列升至 4.1.11、undici 升至 7.30.0，保留既有主版本；Node 22 下 `npm ci`、34 项前端测试、96 路径/114 Controller 操作的契约检查、生产构建通过，`npm audit` 为 0。页面和业务代码未变。

第三次 CI `37118921584` 的 verify 全部通过，镜像 Trivy 阶段发现 Jackson 2.21.4/3.1.4 的已修复 HIGH 问题，以及 Browser Worker 缓存层的 OpenSSL 3.0.13-0ubuntu3.15（修复版 3.16）。`services/backend/build.gradle.kts` 用两个 BOM 将 Jackson 对齐为同系列补丁 2.21.7/3.1.7；Browser Worker Dockerfile 增加 `OS_SECURITY_PATCH_REVISION` 显式刷新系统包升级层，保留 Playwright 1.52.0 及其对应浏览器。镜像扫描、签名和 GitOps 门禁保留。

暂未验证开仓、成交和平仓链路；没有创建测试订单，OMS 仍为 0。不以空订单对账完成证明交易执行成功。

## 回滚

数据库无迁移。回退本次 Bybit 应用提交即可恢复旧映射；当前逐仓账户届时会再次因缺可用余额同步失败。不得为回滚修复而自动切换账户保证金模式或启用实盘。SDB-SCA 模型多样性配置与本次账户修复独立。
