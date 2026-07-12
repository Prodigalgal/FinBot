# P4 量化验证与 Oracle K8S 生产化需求

## 背景

FinBot 已形成“信息源采集 -> 传统/AI 清理 -> AI 压缩 -> 研究分析 -> 产品映射 ->
多 Agent 多轮辩论 -> 人工复核 -> Gate TestNet / Bybit Demo 模拟执行 -> 结果评估”的主链路。
最近两轮审计确认，下一阶段的核心不再是增加更多 AI 角色，而是补齐量化验证真实性、
订单与持仓生命周期、独立硬风控，以及云上长期运行所需的安全和可观测性。

本专案将功能、流程、UI/UX、代码架构、可维护性审计与同类产品差距整合为同一交付计划，
目标运行环境为 Oracle Cloud 双区 K8S 集群。

## 目标

- 将现有研究闭环升级为可重复、可审计、可解释的量化验证闭环。
- 在 AI 决策与模拟交易之间建立交易所规格感知、不可被 AI 绕过的独立风险引擎。
- 建立订单、成交、持仓、退出、对账和异常恢复的完整模拟交易生命周期。
- 以单管理员安全边界将 FinBot 部署到 Oracle Cloud K8S，并保留向多用户/RBAC 演进的边界。
- 降低核心大文件和跨层耦合，保证新增能力可测试、可替换、可回滚。

## 范围

### P0：云上部署与安全可靠性

- 增加默认关闭、云上强制开启的管理员认证；保护全部配置、研究、账户和执行 API。
- 管理员会话使用 HttpOnly、SameSite Cookie；登录接口限速，生产环境要求 HTTPS/Secure Cookie。
- 增加独立 liveness/readiness；readiness 验证 SQLite、运行目录和关键配置，不与长耗时外部依赖耦合。
- K8S 保持 `replicas=1`、`Recreate`、单 Pod Web+Worker 和 Longhorn RWO PVC，直到迁移 PostgreSQL。
- 增加 NetworkPolicy、禁用 ServiceAccount token、RuntimeDefault seccomp、资源与临时目录约束。
- 模拟提交默认关闭，Mainnet 私有 API 和真实交易继续代码级硬禁止。
- 使用 GitOps 管理 namespace、PVC、Deployment、Service、HTTPRoute、Secret 引用和监控对象。
- 固定 Gate/Bybit 出口到 Oracle 新加坡节点；代理不可用时显式失败，不允许直连回退。
- 建立 PVC 备份/恢复说明，并在首次部署前保存可恢复快照或文件级备份。

### P1：量化验证与执行风控

- 新增事件驱动的模拟回放领域，不再把“建议到期结果统计”称为撮合级回测。
- 回放至少支持 candle/mark price、手续费、资金费、可配置滑点、订单延迟和部分成交模型。
- 增加交易所合约规格快照：合约乘数、数量/价格步长、最小名义、杠杆范围、维持保证金率和风险档位。
- 增加强平模拟与逐笔风险预算；用户输入最大可亏金额和止损距离，系统反推允许名义与杠杆。
- 极限杠杆只作为 Paper/TestNet 实验，逐仓、强制止损、禁止加仓/马丁，默认不开放 500X。
- 风险引擎提供最大单笔损失、日损失、连续止损、回撤、相关暴露和强平距离硬门禁。
- 建立 OMS 状态机，覆盖 planned/submitted/partial/filled/cancelled/rejected/expired/reconciled。
- 支持 reduce-only 退出、止盈止损计划、幂等 cancel/replace、断线恢复和交易所状态对账。
- AI 只能产出方向、置信度和解释，不能控制 host、凭据、任意请求、最终数量或绕过风险规则。

### P2：策略实验、组合管理与产品体验

- 新增样本内/样本外、walk-forward、Monte Carlo、参数敏感性和前视偏差检测。
- 输出费用后收益、最大回撤、Sharpe、Sortino、Calmar、胜率、盈亏比和 Benchmark Alpha。
- 建立策略/工作流/模型实验注册表，支持 control/challenger、数据版本和可重复运行。
- 增加 Agent 消融与贡献度分析，区分“更多辩论”与“实际改善结果”。
- 增加策略级资本分配、风险预算、收益归因和交易所/产品/策略多维 PnL。
- 统一研究、辩论、建议、风险、订单和持仓时间线，减少用户跨页面寻找上下文。
- 补齐 Prometheus 指标、结构化日志、告警规则、数据新鲜度和任务积压监控。
- 逐步将 Web 路由、领域服务、持久化 repository 和 UI 页面从大文件拆到稳定模块。
- 建立后端契约测试、迁移测试、前端组件/交互测试和浏览器回归基线。

## 非目标

- 本专案不开放真实盘交易、资金划转、提现或 Mainnet 私有写 API。
- P0 不迁移 PostgreSQL、不做多副本 Web/Worker，也不承诺多租户。
- 不把 LLM 输出、隐藏思维链或未经证据支持的价格预测当作交易事实。
- 不一次性重写现有代码；重构必须围绕明确边界，保持 API 和数据兼容。
- 不以“高杠杆保证金少”为理由降低强平、手续费、滑点或路径风险门禁。

## 架构约束

- `finbot/web/` 只处理 HTTP 契约、认证和 DTO，不承载量化或交易规则。
- `finbot/backtest/` 负责市场回放、成交模型和绩效计算。
- `finbot/risk/` 负责独立风险政策、保证金和强平计算，不依赖 LLM。
- `finbot/execution/` 负责 OMS、退出计划和交易所对账；交易所 adapter 只做协议转换。
- `finbot/storage/` 通过聚合专用 repository 暴露持久化，不向 UI 泄露表结构。
- 所有时间序列计算使用明确 UTC 时间和 point-in-time 数据，禁止未来数据回填。
- 所有外部写入具备幂等键、超时、有限重试、审计记录和显式错误分类。
- 新增 schema 采用 additive migration；旧记录可读，回滚不依赖删除数据。

## Oracle K8S 部署契约

- 目标集群：`kubernetes-admin@sg-osaka-dualstack`，核心节点为 Oracle Cloud 新加坡/大阪 ARM64。
- 工作负载默认仅调度 `infra.mnnu/node-class=core-public`，不使用 edge-only 节点。
- 状态卷使用 Longhorn `ReadWriteOnce`，初始 20Gi；应用更新保持 `Recreate`。
- 外部入口使用 Envoy Gateway `gateway-system`，域名计划为 `finbot.mnnu.eu.org`。
- 镜像使用不可变 tag，并推送到现有受控 registry；manifest 禁止 `latest`。
- Secret 只从集群运行时创建或既有 Secret 管理链注入，不提交明文。
- 公网路由启用前必须满足：认证开启、Secure Cookie、真实交易硬禁止、readiness 通过。

## 验收标准

- P0：认证绕过、匿名敏感 API、错误健康探针、直连代理回退和 Mainnet 私有写入均有失败测试。
- P1：同一输入数据、规格和配置产生确定性回测；费用、滑点、资金费和强平影响可解释。
- P1：模拟订单从计划到退出/对账有完整状态历史，重复请求不产生重复订单。
- P2：walk-forward/Monte Carlo/前视检查在样本不足时输出明确不可用状态，不制造伪指标。
- 后端全量测试、`compileall`、前端 build/test、Kustomize 渲染和容器启动验证通过。
- Argo CD Application `Synced/Healthy`，Web/Worker/egress proxy Ready，日志无循环异常。
- 线上 `/health/live`、`/health/ready`、登录、受保护 API、即时研究和只读账户链路通过。
- 完成镜像 tag、Git revision、Pod、PVC、HTTP 状态、回滚点和遗留风险记录。

