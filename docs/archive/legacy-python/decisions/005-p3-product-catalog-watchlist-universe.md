# ADR-005：产品目录、Watchlist 与 Hybrid Universe 分层

## 状态

已接受，2026-07-11。

## 背景

FinBot 已有 `canonical_products`、`venue_instruments`、场所别名、行情快照和 Hybrid Universe，但用户只能通过运行配置中的固定 symbol 间接影响候选，无法浏览完整产品目录、建立个人关注列表或明确控制研究优先级。把 Watchlist 直接等同 Universe 会让所有关注项触发昂贵研究；只保存 symbol 又无法稳定区分交易所和市场类型。

## 决策

- 产品目录继续分为跨场所 `canonical_products` 与具体 `venue_instruments`，不建立第二套产品主数据。
- 新增 `watchlists` 与 `watchlist_items`。关注项绑定规范化产品，并可选择具体首选合约。
- 当前使用固定 `owner_id=local` 和一个自动创建的默认列表；schema 为后续身份系统预留所有权字段。
- 关注模式分为 `monitor`、`research`、`pinned`。只有后两者进入 Hybrid Universe 候选。
- Universe 优先级为 `user_pinned` > `user_watchlist` > 旧 `watchlist` > `research` > `market_rank`。
- 所有用户来源仍经过 `_eligible` 门禁，不允许 Watchlist 绕过产品活跃性、市场、报价资产、流动性或点差约束。
- 保留现有 `/api/v1/instruments` 和 `autonomous.symbols`，新能力通过增量 API 与增量字段接入。

## 取舍

- 不直接把所有产品交给 LLM。目录同步是全量低成本动作，深度行情、证据收集和 Council 只作用于受控 Universe。
- Watchlist 按产品去重，避免用户在同一列表重复关注多个场所副本；首选合约提供精确执行目标。
- `pinned` 采用高分候选而非绕过筛选，保证用户意图和系统安全边界同时成立。
- P3.1 不引入认证；API 也不暴露可伪造的 owner 参数。真正多用户时再引入身份上下文和迁移策略。

## 兼容与回滚

- 新表由 `create table if not exists` 增量创建，不改写已有产品、Universe 或配置数据。
- 旧 Universe 配置不含用户 instrument IDs 时行为不变。
- 回滚应用版本后新表会保留但不会被旧版本读取；无需数据回填或破坏性迁移。
- 回滚点是移除新 API/UI 与 Universe 用户来源注入；现有固定 symbol 和市场排名路径仍可独立运行。

