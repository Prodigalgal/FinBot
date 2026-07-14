# P3 产品研究工作台专案

## 目标

- 将产品库、用户 Watchlist、Hybrid Universe、研究流程、AI Council 和最终建议整合为一个可持续推进的产品研究工作台。
- 先交付“浏览产品 -> 加入关注 -> 设置研究模式 -> 进入 Universe 候选 -> 进入既有研究与辩论链路”的可用闭环。
- 默认提供本地用户和默认关注列表，避免首次启用需要额外配置。
- 产品研究链保持 public-data-only；仅隔离的 Gate TestNet / Bybit Demo 执行层可读取模拟账户并提交模拟订单，真实盘继续硬禁止。

## 用户角色与核心痛点

- 研究用户：需要从完整交易所产品目录中搜索、筛选和关注产品，而不是只依赖固定 symbol。
- 运营用户：需要明确区分“仅观察”“加入研究”和“优先研究”，并知道它们如何影响自动循环。
- 风险复核用户：需要追踪产品、研究证据、Council 结论和建议之间的可审计关系。

## P3.1 范围

- 产品中心：分页搜索，按交易所、市场类型、产品类型和活跃状态筛选。
- 产品详情：展示规范化产品信息、场所合约、行情快照、精度、最小数量、最小名义价值和杠杆元数据。
- Watchlist：默认列表、自定义列表、关注项增删改、首选场所合约、标签、备注和研究模式。
- Universe 集成：`research` 和 `pinned` 模式使用稳定 `instrument_id` 进入 Hybrid Universe 候选；`monitor` 只展示不进入自动研究。
- Web UI：桌面分栏检查器、移动端详情抽屉、加载态、错误态、空态和分页。

## 非目标

- P3.1 不实现登录、团队权限和云端多租户。
- 产品中心不拉取私有交易所数据或提供真实交易按钮；模拟执行仅由隔离的多交易所执行层完成。
- 不对所有产品逐一执行深度行情、LLM 分析和多 Agent 辩论。
- 不在本阶段实现通知投递、组合回测或真实收益归因。

## 后续里程碑

- P3.1B：Gate TestNet + Bybit Demo 并发 AI 模拟交易；真实盘继续硬禁止。
- P3.2：研究与建议历史、版本对比、运行重放、失败续跑、人工复核收件箱。
- P3.3：Council Workflow 草稿/发布/版本/回滚、节点单测和运行前成本预估。
- P3.4：Shadow Portfolio、回测、置信度校准、受控反思和通知策略。
- 可靠性主线：持续收敛 SQLite 写竞争；达到并发和数据量阈值后评估 PostgreSQL。

## 数据与状态规则

- `canonical_products` 是跨交易所产品身份；`venue_instruments` 是可选择的具体交易所合约。
- Watchlist 项绑定 `product_id`，可选绑定一个 `preferred_instrument_id`；首选合约必须属于该产品。
- 研究模式仅允许：`monitor`、`research`、`pinned`。
- `pinned` 代表最高候选优先级，不绕过活跃状态、市场范围、报价资产、点差和成交额门禁。
- 删除 Watchlist 项不删除产品目录、行情、研究历史或 Universe 历史。
- 当前默认 `owner_id=local`；API 不接受任意 owner，避免伪多用户边界。
- SQLite 使用 WAL；市场快照必须具备 `(instrument_id, captured_at desc)` 索引，Web 进程按数据库路径只初始化一次 schema，避免目录同步期间阻塞产品查询。

## API 契约

- `GET /api/v1/products`：返回可分页、可筛选的场所合约列表，并附带规范化产品与 Watchlist 状态。
- `GET /api/v1/products/{product_id}`：返回规范化产品、全部场所合约和 Watchlist 状态。
- `GET/POST/PATCH/DELETE /api/v1/watchlists`：管理本地 Watchlist；默认列表不可删除。
- `PUT/DELETE /api/v1/watchlists/{watchlist_id}/items/{product_id}`：幂等保存或删除关注项。
- 保留现有 `/api/v1/instruments` 和 `autonomous.symbols` 行为，确保兼容现有调用方。

## 验收标准

- 首次启动自动创建“默认关注列表”，用户无需配置即可添加产品。
- 产品列表支持服务端分页、搜索和组合筛选，列表与总数一致。
- Watchlist 写入幂等；非法模式、未知产品、跨产品首选合约返回明确 4xx。
- `monitor` 不进入 Universe；`research` 以 `user_watchlist` 来源进入；`pinned` 以 `user_pinned` 来源进入。
- 固定 `autonomous.symbols` 继续以原 `watchlist` 来源工作。
- 桌面与 390px 窄屏均可完成搜索、选中、关注、修改模式和取消关注。
- Python 测试、前端构建、HTTP smoke 和浏览器 smoke 通过。

## 测试方式

```powershell
python -m compileall finbot
python -m unittest discover -s tests -v
cd web-ui
npm run build
```

- API smoke：产品分页、产品详情、默认 Watchlist、关注项 PUT/DELETE、非法输入。
- 浏览器：1536x1024 与 390x844；检查交互、控制台、布局溢出和移动详情抽屉。

## 2026-07-11 运行态验收

- 产品目录共 3,032 个活跃场所标的，其中 Gate spot 2,203、Gate perpetual 829；Bybit Linear 因当前代理出口地区限制未同步，不写入伪造目录。
- 产品列表在 Worker 运行时实测约 215 ms；模拟执行状态约 248 ms。修复前两个接口均会因 SQLite 竞争超过 20 秒。
- Watchlist 加入、`research` 模式、标签/备注保存、过滤与删除均通过浏览器回读；验收数据已清理。
- 390x844 下页面无横向溢出，详情抽屉可完成完整配置，浏览器 console 无 error/warn。
