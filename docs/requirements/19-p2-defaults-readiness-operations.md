# P2 默认配置与生产运营

状态：实施中（2026-07-11）

## 目标

- 为内置 AI 模型提供可审计的默认 token 费率，让成本治理开箱可用。
- 提供推荐、省成本、深度研究三组默认配置档案，降低首次启用配置量。
- 提供统一启用就绪度，明确 AI、Council、公共行情、自动循环和 Worker 的阻断项。
- 保持系统 advisory-only、公共数据优先和 Firecrawl 代理池硬约束。

## 范围

- `finbot/config/ai_sites.py`：型号绑定费率、来源和核对日期。
- `finbot/config/setup_profiles.py`：默认配置档案、预览、应用和就绪度。
- `finbot/ai/governance.py`：按当前匹配费率重算旧调用的治理成本。
- `finbot/web/service.py`：P2 setup API。
- `web-ui/src/`：快速启用和费率元数据界面。
- `config/*.example.json`、测试和运维文档。

## 非目标

- 不在服务启动时静默写入配置或自动启用付费调用。
- 不把 API key、代理订阅、宏观密钥放入配置档案。
- 不为未知或不匹配的模型套用近似费率。
- 不开放下单、撤单、转账或私有账户能力。
- 不在本阶段引入多租户、登录鉴权或外部分布式队列。

## 业务契约

### AI 默认费率

- 费率以 USD / 1M tokens 表示，输入价采用 cache miss 保守口径。
- `deepseek-v4-flash`：输入 `$0.14`，输出 `$0.28`。
- `mimo-v2.5-pro`：输入 `$0.435`，输出 `$0.87`。
- 每组默认费率必须记录 `pricing_model`、`pricing_source_url`、`pricing_checked_at` 和 `pricing_basis`。
- 调用模型与 `pricing_model` 不一致时成本必须为 `unknown`。
- 历史 invocation 没有成本但型号匹配时，治理报告允许按当前费率重算，并标记重算数量；不改写原始 invocation。

### 默认配置档案

- 内置 `recommended`、`economy`、`deep_research`。
- 档案只能包含 `CONFIG_FIELD_SPECS` 中的非敏感、非代理策略、非环境映射字段，避免覆盖 `.env` 和代理运行态。
- 默认应用语义为 fill-missing：只补未配置值，保留用户现有覆盖。
- 用户可显式选择覆盖非敏感值；任何模式都不得清理或覆盖敏感值。
- 应用结果必须返回 applied、preserved 和 skipped keys，便于审计。

### 启用就绪度

- 检查至少一个启用 AI 站点具有 key。
- 检查启用 AI 站点默认模型有匹配费率。
- 检查 Council 至少有两个启用角色、三个分析阶段和 Chair。
- 检查公共行情 provider、自动循环和 active Worker。
- 返回 `ready`、`needs_attention` 或 `blocked`，并区分 required/warning。

## API

- `GET /api/v1/setup`：返回 profiles、readiness 和默认数据摘要。
- `POST /api/v1/setup/apply`：应用指定档案，参数包含 `profile_id`、`preserve_existing`。

## 验收标准

- 无 `ai_sites.json` 时，AI 配置 API 直接返回上述默认费率与来源。
- 模型匹配时 P1 治理成本为 known；模型不匹配时仍为 unknown。
- 新目录可一键应用推荐档案，已有配置默认不被覆盖。
- Web 可查看就绪度、选择档案并应用；桌面和 390px 窄屏无溢出。
- `python -m compileall finbot`、全量单测和 `npm run build` 通过。
- 常驻 Web/Worker 更新后仍运行，setup API 和现有自动闭环可用。

## 测试方式

```powershell
python -m compileall finbot
python -m unittest discover -s tests
cd web-ui
npm run build
```
