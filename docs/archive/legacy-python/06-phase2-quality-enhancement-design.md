# Phase 2 质量增强设计

## 阶段目标

Phase 1 已经完成多源采集、原始证据保存、去重、基础事件候选和 AI research package。

Phase 2 的第一刀是质量增强：让每个事件候选不只是“有几篇文章”，还要能回答：

- 来源是否足够可信。
- 是否有 T1 官方或一级信息源确认。
- 是否有独立二级来源交叉验证。
- 是否存在方向性冲突。
- 是否已经附带行情确认材料。
- AI 研究层下一步应该补什么证据。

## 本次实现范围

新增 `finbot.quality.event_quality.EventQualityEvaluator`，在标准化阶段对 event candidate 写入质量元数据：

```json
{
  "quality_score": 0.72,
  "confirmation_state": "likely",
  "priority": "P1",
  "evidence_tiers": {"T1": 1, "T2": 2},
  "source_categories": {"macro": 1, "broad_market_news": 2},
  "market_confirmation": {
    "status": "available",
    "matched_assets": ["BTCUSDT", "NAS100"]
  },
  "review_flags": ["no_t1_official_confirmation"],
  "conflict_flags": [],
  "suggested_followups": [
    "Search official or primary sources before treating this as confirmed."
  ]
}
```

`research-input-latest.json` 同步提升这些字段：

- `quality_summary`
- `event_candidates[].priority`
- `event_candidates[].confirmation_state`
- `event_candidates[].quality`
- `event_candidates[].market_confirmation`

## 评分逻辑

质量分不是交易信号，只是研究就绪度。

当前规则：

- 平均 source trust 是基础分。
- 多来源、多文档提高分数。
- 有 T1 官方来源加分。
- 有重叠资产的 market_data 证据加分。
- 新鲜度小幅加分。
- 冲突语言扣分。
- 官方索引页、列表页、RSS landing page 会被降权，并提示继续抓详情页。
- 新闻搜索结果如果标题/摘要和 watchlist 主题弱相关，会标记 `weak_topic_match` 并降权。

## 事件聚类 v1

Phase 1 的 event key 更接近标题去重。Phase 2 改成：

```text
category_family:asset_anchor:event_anchor_terms
```

示例：

- `energy:oil:oil crude inventory eia`
- `macro:macro:fed rate inflation yields`
- `crypto:crypto:bitcoin etf sec`

这样后续多个来源报道同一类事件时，可以先按主题锚点合并，再交给 AI 或更细的聚类器继续拆分。

确认状态：

- `confirmed-by-official-and-secondary`
- `likely`
- `market-context-ready`
- `watch`
- `weak`

优先级：

- `P0`：高质量且高影响类别，例如 energy、macro、geopolitics、policy。
- `P1`：质量较高，可进入 AI 研究队列。
- `P2`：可观察，需要补证据。
- `P3`：弱证据，先不让 AI 强分析。

## 后续 Phase 2 增量

后续继续接：

- 官方日历：EIA、FOMC、BLS、BEA 发布时间表。
- SEC / FRED / EIA 结构化源增强。
- StockTwits、Polymarket 情绪和概率源。
- Firecrawl credit 预算控制。
- provider 级节流和并发配额。
