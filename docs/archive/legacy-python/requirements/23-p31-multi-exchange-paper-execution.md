# P3.1B 多交易所 AI 模拟交易

## 目标

- 允许通过研究、真正多轮 Council、交易合成、产品筛选、组合风险和 AI 治理门禁的最终 BUY/SELL 决策进入模拟交易。
- 首版并发支持 Gate Futures TestNet 与 Bybit Demo Trading，真实盘始终硬禁止。
- 将计划、跳过、提交、成交、拒绝和失败状态按交易所独立落库，支持 Web 查看与审计。

## 范围

- Gate USDT 永续和 Bybit USDT Linear 的 Mainnet 公共产品目录、ticker 与 candle。
- 统一 `PaperExecutionAdapter` 契约，以及 Gate API v4 TestNet、Bybit V5 Demo 两个 adapter。
- 自动循环新增最终 `paper_execution` 步骤，按启用交易所并发执行。
- 每交易所独立凭据、超时、熔断、幂等、单轮上限、单笔名义上限、最低置信度和持仓门禁。
- Web 配置、readiness、最近模拟订单和只读 API。

## 非目标

- 不支持真实盘 host、交割期货或首版之外的交易所。
- 不允许 LLM 直接构造任意 HTTP 请求、价格、数量、杠杆或 API host。
- 不自动转账、不调整账户模式、不修改杠杆、不撤销用户手工订单。
- P3.1B 不实现自动止盈止损单和跨轮平仓策略；首版遇到同向或反向既有持仓均跳过，避免仓位叠加或自动反转。
- 不将一家交易所失败隐式改成另一家 fallback；并发目标必须由配置显式启用。

## 配置与凭据

- Gate host 固定为当前 API v4 官方文档列出的 `https://api-testnet.gateapi.io/api/v4`；旧 `fx-api-testnet.gateio.ws` 不再作为私有执行入口。
- Bybit 私有 host 固定为 `https://api-demo.bybit.com`；公共行情优先使用 Mainnet `https://api.bybit.com`，仅在地区/访问阻断时切换官方备用 `https://api.bytick.com`。
- 交易所 VLESS 订阅支持 `exchange.vless_preferred_node_names` 稳定重排；只保存节点标签，不复制 UUID，未命中时保持订阅原顺序。
- 本地诊断与开发运行可通过敏感配置 `exchange.hysteria2_urls` 提供多条 Hysteria2 节点；运行时按配置顺序优先于 VLESS，并对单节点 bridge 启动失败做隔离。节点 URL、密码和 obfs 密码不得进入 API、日志、报告或仓库。
- K8S 默认使用 `http://finbot-egress-proxy:8888`，由带 `infra.mnnu/location=sg` 标签的固定出口节点承载；FinBot Pod 不运行 VLESS bridge。
- 密钥从敏感运行时配置或 `GATE_TESTNET_API_KEY`、`GATE_TESTNET_API_SECRET`、`BYBIT_DEMO_API_KEY`、`BYBIT_DEMO_API_SECRET` 读取，API 永不回显原文。
- 默认关闭自动提交；缺少某家凭据只阻塞该 adapter，不影响另一家。
- 默认每家每轮最多 1 单、单笔最大 100 USDT 名义价值、最低置信度 0.70；该上限可覆盖 Bybit 当前 BTC 最小数量且仍保持小额模拟。

## 共享执行门禁

- 决策必须为最终 `candidate`，action 为 BUY 或 SELL，且置信度达到模拟执行阈值。
- 决策必须包含 target 与 invalidation；Portfolio Risk 与 AI Governance 必须通过。
- 产品必须能精确映射到交易所的活跃 USDT 永续合约。
- 模拟账户不得已有该合约未平仓位，数据库不得已有相同 decision/adapter 的执行记录。
- 数量由 adapter 根据目标名义价值、Mainnet 最新价和交易所合约规格计算；AI 不能直接指定。

## 验收标准

- 固定模拟 host 有单元测试保护，真实盘或自定义 host 均不可进入私有执行 client。
- Gate HMAC-SHA512 与 Bybit HMAC-SHA256 签名有确定性测试，不记录 key、secret 或 SIGN。
- BUY/SELL、数量步长、最小数量、最小名义价值和客户端订单 ID 映射正确。
- 相同 `decision_id + adapter_id` 重试不重复下单；同合约存在持仓时跳过。
- 两个 adapter 并发运行、错误隔离，结果按交易所汇总。
- 测试使用 mock transport，不会误发模拟或真实订单；具备专用 key 后再执行受控 live smoke。
- 专用只读探针必须输出脱敏报告，并以当前 key+secret 的不可逆短指纹防止旧探针结果误匹配新凭据；Web 只显示验证状态和错误分类。

## 官方契约

- Gate：TestNet 与 real trading host 分离；Futures `size` 为合约张数。
- Bybit：Demo Trading 是隔离账户，公共数据与 Mainnet 相同，私有 API 使用 `api-demo.bybit.com`。

## 当前运行边界

- `paper_execution.enabled=true`，`paper_execution.submit_orders=false`；最近自动循环 13/13 步通过，模拟执行未发送写请求。
- Gate TestNet 已切换到当前官方基址 `api-testnet.gateapi.io`；现有凭据只读鉴权通过，持仓查询返回空结果，探针确认未发送写请求。
- Bybit Demo 已通过订阅优先节点完成标准只读鉴权，BTCUSDT 当前无未平仓位；探针确认未发送写请求。
- 2026-07-12 复核发现此前 16 个 VLESS 候选的 Cloudflare 入口未变，但真实出口已从 DE/GB/SG 漂移为 US 或超时，因此标签和入口地址不能作为持续可用性证明。当前 4 个 Hysteria2 候选均通过两轮 Mainnet/Demo 公共请求和 Demo 私有只读鉴权，三个 JP 出口优先、SG 末级备选。
- 两家只读探针均已通过；首次受控并发模拟下单已完成。执行 run `c3957c42717a374de81ecf53d1221c55` 中 Gate TestNet 为 `filled`、Bybit Demo 为 `submitted` 且后续持仓确认，两家名义均低于 100 USDT。
- `submit_orders` 已恢复为 `false`；该决策仅保留 2 条 adapter 唯一执行记录，模拟仓位未自动平仓。
- 新加坡出口已实测同时访问 Bybit Mainnet、Bybit Demo 与 Gate TestNet；K8S manifest 已通过目标集群 `kubectl kustomize | kubectl apply --dry-run=client`，尚未执行正式集群部署。
