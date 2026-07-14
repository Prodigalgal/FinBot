# ADR-001：P0 Council、Hybrid Universe 与常驻 Worker

## 状态

Accepted - 2026-07-10

## 决策

### Council

- FinBot 定义自己的 Council domain contract，不将 API、存储或配置暴露为 AutoGen/LangGraph 类型。
- 首个执行后端复用现有 `OpenAICompatibleClient`、AI site 配置和 SQLite 审计链。
- P8 产品建议委员会作为第一个 `CouncilTemplate`；现有 `AIDebateCouncilRunner` 变为领域适配器。
- 默认三阶段讨论：独立分析、交叉质询、立场修订；Chair 在分析轮次之后合成。
- 每个角色独立配置 provider/model/prompt，允许相同模型但不假定角色提示词等于模型多样性。

### Hybrid Universe

- 同步 enabled providers 的完整 instrument 元数据，但不逐一执行深度行情或 AI 分析。
- Universe 先做确定性过滤与排名，再把有限候选交给 operator workbench 和 Council。
- 标准产品与交易场所 instrument 分离；所有事件到 symbol 的映射必须经过 alias/instrument registry。
- P0 使用现有交易所公共端点与 proxy runtime，同步 Gate/Binance/Bybit；CCXT 保持可选依赖，不进入强制运行路径。

### Worker

- 生产自动循环从 Web 进程分离为 `finbot-worker`。
- SQLite 保存 run requests、scheduler state、lease 和 heartbeat；单 worker 运行，重复实例通过租约互斥。
- Web 手动触发改为入队；嵌入式 scheduler 只保留显式开发兼容开关。

## 取舍

- 原生 Council 后端减少新依赖和配置迁移，但需要本项目负责轮次状态机、checkpoint 和终止逻辑。
- 全量产品元数据加 Top N 深度分析兼顾覆盖度和成本，不提供“所有产品每轮 AI 分析”的假象。
- SQLite worker 适合当前单机环境；未来横向扩展时迁移 PostgreSQL/外部队列，不改变 Council 和 Universe 领域契约。

## 兼容策略

- 保留现有 autonomous API、报告名、`ai_debate_councils`、`ai_debate_messages` 和 `ai_trade_decisions`。
- 新字段通过 additive schema migration 增加；不删除历史数据。
- 旧 AI 配置缺少 Council templates 时加载内置默认模板，不要求用户手工迁移。
