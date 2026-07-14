# ADR-006：隔离的多交易所模拟执行层

## 状态

已接受，2026-07-11。

## 背景

FinBot 原有 `PaperLedgerPlanner` 只生成本地 proposal，所有 policy 均禁止交易所订单 API。用户要求 AI 最终决策并发进入 Gate TestNet 和 Bybit Demo Trading。即使是模拟环境，带签名订单仍是外部写操作，不能把私有 API 权限扩散到 LLM、Council、产品筛选或通用 HTTP client。

## 决策

- 领域层只依赖统一 `PaperExecutionAdapter`；Gate 与 Bybit 的签名、合约规格和响应模型封装在各自 adapter。
- 私有 host 编译期固定为当前官方 Gate TestNet `api-testnet.gateapi.io` 和 Bybit Demo `api-demo.bybit.com`，客户端拒绝真实盘及任意自定义 host。
- Mainnet 公共行情负责研究、决策与数量参考；模拟 API 只负责账户、持仓和订单状态。
- 执行器重新校验 provider、market type、action、confidence、risk/governance、产品状态、模拟持仓和限额。
- AI 不提供最终订单数量。adapter 根据名义限额和交易所规格做确定性量化。
- `decision_id + adapter_id` 数据库唯一约束与交易所 client order ID 共同保证幂等。
- Portfolio Risk 的 provider concentration 按配置的执行 adapter 等额名义分配计算；建议中的 provider 只表示行情/候选来源，不冒充实际持仓场所。
- `risk_gate=warning` 仍要求人工复核并完整展示原因，但不等同硬阻断；只有没有 `severity=warning` 的超限原因才阻止模拟执行。
- adapter 使用有界线程池并发；一家失败只产生该 adapter 的失败记录，不回退真实盘、不取消另一家的成功结果。
- 默认关闭模拟提交，凭据从敏感配置或环境变量读取，不进入日志、报告或数据库。
- 凭据 readiness 不以“字段非空”为准；只读探针结果必须与当前凭据指纹匹配，验证失败时 Web 和执行层保持阻断。
- Bybit 公共数据允许在官方 `api.bybit.com` 与 `api.bytick.com` 之间做显式 host failover；私有 Demo host 不参与 failover。
- VLESS 在线订阅允许按非敏感节点标签做稳定优先排序；UUID 仍只存在于订阅响应和临时 bridge 配置中。
- 生产 K8S 使用独立 `finbot-egress-proxy` Service 统一转发交易所流量；代理 Pod 通过 `infra.mnnu/location=sg` 固定到已验证的新加坡出口，FinBot Web/Worker Pod 不绑定地域。
- 代理入口由 NetworkPolicy 限制为同 namespace 的 FinBot Pod，且只允许 HTTPS `CONNECT:443`。缺少合格地域标签时代理保持 `Pending`，不得静默调度到未验证出口。

## 取舍

- 使用已有 `httpx` 实现两个最小签名 client，避免引入两套完整 SDK；签名算法与官方 SDK/文档做确定性测试。
- 首版只开 USDT 永续/Linear，保持跨交易所产品映射清晰。
- 既有持仓时跳过，不自动加仓、反转或平仓。该策略牺牲连续调仓，换取初期可解释与可控。
- 并发不是 fallback；用户明确选择向哪些模拟交易所提交，避免隐藏的执行语义。
- Gate 返回 `order_size_min=0` 时按最小 1 张归一；AI 仍不能提供数量。首版不附加自动 TP/SL，退出策略在两家 adapter 具备一致契约后单独交付。
- 本地 VLESS/Hysteria2/SSH bridge 只用于开发诊断与受控 smoke，不进入容器，也不作为生产常驻依赖。Hysteria2 直连 URL作为敏感运行时配置保存，bridge 配置在进程关闭后立即删除。应用镜像同时提供非 root `tinyproxy` 二进制，由独立 Deployment 以 UID 10001、只读根文件系统运行。
- 节点标签、Cloudflare 前置地址和一次成功都不等于稳定出口。运行验收必须记录真实出口地区，并同时通过 Mainnet、Demo 公共 API 与 Demo 私有只读请求；固定候选仍需定期复测。

## 兼容与回滚

- 默认配置关闭，升级不会产生外部写操作。
- 新表和自动循环步骤为增量；关闭 `paper_execution.enabled` 即可运行时回滚。
- 移除执行层后，研究、AI 决策、产品建议和本地 paper proposal 仍独立可用。
