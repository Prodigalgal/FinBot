from __future__ import annotations

import json
import threading
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RUNTIME_CONFIG_VERSION = 1
RUNTIME_CONFIG_FILENAME = "runtime_config.json"
PROXY_POLICY_KEYS = {
    "proxy_policy.exchange.allow_direct": ("exchange", "allow_direct"),
    "proxy_policy.exchange.binance.allow_direct": ("exchange:binance", "allow_direct"),
    "proxy_policy.exchange.bybit.allow_direct": ("exchange:bybit", "allow_direct"),
    "proxy_policy.exchange.gate.allow_direct": ("exchange:gate", "allow_direct"),
}


@dataclass(frozen=True)
class ConfigFieldSpec:
    key: str
    group: str
    label: str
    kind: str
    default: Any = None
    help: str = ""
    settings_field: str | None = None
    sensitive: bool = False
    hot_reload: bool = True
    restart_required: bool = False
    options: tuple[str, ...] = ()
    minimum: float | None = None
    maximum: float | None = None
    multiline: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "key": self.key,
            "group": self.group,
            "label": self.label,
            "kind": self.kind,
            "default": None if self.sensitive else self.default,
            "help": self.help,
            "sensitive": self.sensitive,
            "hot_reload": self.hot_reload,
            "restart_required": self.restart_required,
            "options": list(self.options),
            "minimum": self.minimum,
            "maximum": self.maximum,
            "multiline": self.multiline,
        }


CONFIG_FIELD_SPECS: tuple[ConfigFieldSpec, ...] = (
    ConfigFieldSpec("system.data_dir", "基础", "数据目录", "string", "data", settings_field="data_dir", help="下一次请求读取新的 SQLite 和 reports 目录。"),
    ConfigFieldSpec("system.catalog_path", "基础", "信源目录文件", "string", "config/source_catalog.example.yml", help="研究流水线下一次运行生效。"),
    ConfigFieldSpec("system.topics_path", "基础", "主题监听文件", "string", "config/topic_watchlists.example.yml", help="研究流水线下一次运行生效。"),
    ConfigFieldSpec("system.http_user_agent", "基础", "HTTP User-Agent", "string", "FinBot research bot", settings_field="http_user_agent"),
    ConfigFieldSpec("firecrawl.api_base", "Firecrawl", "API 地址", "string", "https://api.firecrawl.dev/v2", settings_field="firecrawl_api_base"),
    ConfigFieldSpec("firecrawl.api_key", "Firecrawl", "API Key", "secret", None, settings_field="firecrawl_api_key", sensitive=True),
    ConfigFieldSpec("firecrawl.auth_mode", "Firecrawl", "认证模式", "select", "keyless", settings_field="firecrawl_auth_mode", options=("keyless", "bearer")),
    ConfigFieldSpec("firecrawl.proxy", "Firecrawl", "单代理", "string", None, settings_field="firecrawl_proxy", sensitive=True),
    ConfigFieldSpec("firecrawl.proxy_pool", "Firecrawl", "代理池", "multiline", None, settings_field="firecrawl_proxy_pool", sensitive=True, multiline=True),
    ConfigFieldSpec("firecrawl.proxy_file", "Firecrawl", "代理文件", "string", "config/firecrawl_proxies.txt", settings_field="firecrawl_proxy_file"),
    ConfigFieldSpec("firecrawl.proxy_ip_family", "Firecrawl", "代理 IP 类型", "select", "ipv4", settings_field="firecrawl_proxy_ip_family", options=("ipv4",)),
    ConfigFieldSpec("firecrawl.proxy_dns_mode", "Firecrawl", "DNS 模式", "select", "remote", settings_field="firecrawl_proxy_dns_mode", options=("remote",)),
    ConfigFieldSpec("firecrawl.vless_subscription_url", "Firecrawl", "VLESS 订阅地址", "secret", None, settings_field="firecrawl_vless_subscription_url", sensitive=True),
    ConfigFieldSpec("firecrawl.vless_subscription_file", "Firecrawl", "VLESS 订阅文件", "string", None, settings_field="firecrawl_vless_subscription_file"),
    ConfigFieldSpec("firecrawl.vless_max_nodes", "Firecrawl", "VLESS 最大节点数", "integer", 8, settings_field="firecrawl_vless_max_nodes", minimum=1, maximum=50),
    ConfigFieldSpec("exchange.proxy", "交易所代理", "单代理", "string", None, settings_field="exchange_proxy", sensitive=True),
    ConfigFieldSpec("exchange.proxy_pool", "交易所代理", "代理池", "multiline", None, settings_field="exchange_proxy_pool", sensitive=True, multiline=True),
    ConfigFieldSpec("exchange.proxy_file", "交易所代理", "代理文件", "string", None, settings_field="exchange_proxy_file"),
    ConfigFieldSpec("exchange.proxy_ip_family", "交易所代理", "代理 IP 类型", "select", "ipv4", settings_field="exchange_proxy_ip_family", options=("ipv4",)),
    ConfigFieldSpec("exchange.proxy_dns_mode", "交易所代理", "DNS 模式", "select", "remote", settings_field="exchange_proxy_dns_mode", options=("local", "remote")),
    ConfigFieldSpec("exchange.vless_subscription_url", "交易所代理", "VLESS 订阅地址", "secret", None, settings_field="exchange_vless_subscription_url", sensitive=True),
    ConfigFieldSpec("exchange.vless_subscription_file", "交易所代理", "VLESS 订阅文件", "string", None, settings_field="exchange_vless_subscription_file"),
    ConfigFieldSpec("exchange.vless_max_nodes", "交易所代理", "VLESS 最大节点数", "integer", 2, settings_field="exchange_vless_max_nodes", minimum=0, maximum=50),
    ConfigFieldSpec("exchange.vless_preferred_node_names", "交易所代理", "VLESS 优先节点标签", "string_list", (), settings_field="exchange_vless_preferred_node_names", help="按顺序优先使用订阅中的精确节点标签；未命中标签时保持订阅原顺序。"),
    ConfigFieldSpec("exchange.hysteria2_urls", "交易所代理", "Hysteria2 首选节点", "multiline", None, settings_field="exchange_hysteria2_urls", sensitive=True, multiline=True, help="每行一个 hysteria2:// URL；只在服务端使用，优先于 VLESS 备选。"),
    ConfigFieldSpec("exchange.hysteria2_max_nodes", "交易所代理", "Hysteria2 最大节点数", "integer", 4, settings_field="exchange_hysteria2_max_nodes", minimum=0, maximum=20),
    ConfigFieldSpec("exchange.sing_box_path", "交易所代理", "sing-box 路径", "string", r"D:\DevlopTools\sing-box\1.13.14\sing-box.exe", settings_field="sing_box_path"),
    ConfigFieldSpec("exchange.proxy_runtime_dir", "交易所代理", "代理运行目录", "string", "data/runtime/proxy", settings_field="proxy_runtime_dir"),
    ConfigFieldSpec("exchange.proxy_policy_file", "交易所代理", "代理策略文件", "string", "config/proxy_policy.json", settings_field="proxy_policy_file"),
    ConfigFieldSpec("exchange.enabled_public_providers", "交易建议", "启用公共行情交易所", "string_list", ("gate:spot", "gate:perpetual", "bybit:linear"), help="全局默认公共行情来源；研究使用 Mainnet 公共 API，不需要私有 key。模拟执行只消费 perpetual/linear 映射。"),
    ConfigFieldSpec("proxy_policy.exchange.allow_direct", "代理策略", "交易所允许直连", "boolean", False, help="只影响交易所公共行情路由；Firecrawl 仍禁止直连。"),
    ConfigFieldSpec("proxy_policy.exchange.binance.allow_direct", "代理策略", "Binance 允许直连", "boolean", False),
    ConfigFieldSpec("proxy_policy.exchange.bybit.allow_direct", "代理策略", "Bybit 允许直连", "boolean", False),
    ConfigFieldSpec("proxy_policy.exchange.gate.allow_direct", "代理策略", "Gate 允许直连", "boolean", False),
    ConfigFieldSpec("ai.provider_order", "AI 压缩", "Provider 顺序", "string_list", ("deepseek", "mimo")),
    ConfigFieldSpec("ai.protocol", "AI 压缩", "协议", "select", "chat", options=("chat", "responses")),
    ConfigFieldSpec("ai.keys_file", "AI 压缩", "密钥文件", "string", "config/ai-provider-keys.env"),
    ConfigFieldSpec("research.dry_run", "研究流水线", "默认仅演练", "boolean", True),
    ConfigFieldSpec("research.run_ingestion", "研究流水线", "默认执行采集", "boolean", False),
    ConfigFieldSpec("research.run_ai_compression", "研究流水线", "默认 AI 压缩", "boolean", False),
    ConfigFieldSpec("research.ai_compression_dry_run", "研究流水线", "AI 压缩默认演练", "boolean", True),
    ConfigFieldSpec("research.run_followups", "研究流水线", "默认补证据", "boolean", False),
    ConfigFieldSpec("research.followups_dry_run", "研究流水线", "补证据默认演练", "boolean", True),
    ConfigFieldSpec("research.max_events", "研究流水线", "最大事件数", "integer", 10, minimum=1, maximum=200),
    ConfigFieldSpec("research.evidence_limit", "研究流水线", "证据处理上限", "integer", None, minimum=1, maximum=5000),
    ConfigFieldSpec("research.limit_cards", "研究流水线", "研究卡片上限", "integer", None, minimum=1, maximum=1000),
    ConfigFieldSpec("operator.symbols", "交易建议", "默认交易标的", "string_list", ("BTCUSDT",)),
    ConfigFieldSpec("operator.providers", "交易建议", "默认行情来源", "string_list", ("gate",), help="交易建议工作台的公共行情来源；默认只启用 Gate，可热更新增加 Binance 或 Bybit。"),
    ConfigFieldSpec("operator.intervals", "交易建议", "默认周期", "string_list", ("1h", "4h", "1d")),
    ConfigFieldSpec("operator.candle_limit", "交易建议", "K 线数量", "integer", 5, minimum=2, maximum=500),
    ConfigFieldSpec("operator.start_bridges", "交易建议", "默认启动代理桥", "boolean", True),
    ConfigFieldSpec("operator.persist", "交易建议", "默认写入报告", "boolean", True),
    ConfigFieldSpec("operator.include_research_context", "交易建议", "带入研究上下文", "boolean", True),
    ConfigFieldSpec("operator.risk_per_trade_pct", "交易建议", "单笔风险百分比", "number", 0.5, minimum=0.0, maximum=10.0),
    ConfigFieldSpec("operator.max_position_notional_pct", "交易建议", "最大仓位名义百分比", "number", 5.0, minimum=0.0, maximum=100.0),
    ConfigFieldSpec("operator.reward_risk_ratio", "交易建议", "盈亏比", "number", 1.6, minimum=0.1, maximum=20.0),
    ConfigFieldSpec("autonomous.enabled", "自动循环", "启用自动循环", "boolean", False, help="开启后常驻 Worker 会按间隔提交并执行完整闭环。"),
    ConfigFieldSpec("autonomous.interval_minutes", "自动循环", "循环间隔分钟", "integer", 60, minimum=1, maximum=1440),
    ConfigFieldSpec("autonomous.continue_on_error", "自动循环", "失败后继续后续步骤", "boolean", True),
    ConfigFieldSpec("autonomous.run_research_pipeline", "自动循环", "运行研究流水线", "boolean", True),
    ConfigFieldSpec("autonomous.run_ingestion", "自动循环", "执行信源采集", "boolean", True),
    ConfigFieldSpec("autonomous.max_initial_jobs", "自动循环", "初始采集任务上限", "integer", 20, minimum=1, maximum=200),
    ConfigFieldSpec("autonomous.run_ai_compression", "自动循环", "执行 AI 压缩", "boolean", True),
    ConfigFieldSpec("autonomous.ai_compression_dry_run", "自动循环", "AI 压缩演练", "boolean", False),
    ConfigFieldSpec("autonomous.run_followups", "自动循环", "执行补证据任务", "boolean", True),
    ConfigFieldSpec("autonomous.followups_dry_run", "自动循环", "补证据演练", "boolean", False),
    ConfigFieldSpec("autonomous.max_events", "自动循环", "研究事件上限", "integer", 10, minimum=1, maximum=200),
    ConfigFieldSpec("autonomous.include_background_council", "自动循环", "复核包含背景项", "boolean", True),
    ConfigFieldSpec("autonomous.run_instrument_catalog", "自动循环", "同步交易产品目录", "boolean", True),
    ConfigFieldSpec("autonomous.universe_mode", "自动循环", "产品 Universe 模式", "select", "hybrid", options=("fixed", "hybrid")),
    ConfigFieldSpec("autonomous.universe_quote_assets", "自动循环", "Universe 计价资产", "string_list", ("USDT",)),
    ConfigFieldSpec(
        "autonomous.universe_max_instruments",
        "自动循环",
        "Universe 场所合约上限",
        "integer",
        12,
        minimum=1,
        maximum=200,
        help="按 provider + market_type + symbol 计数；同一产品在不同交易所或现货/永续市场会分别占用名额。",
    ),
    ConfigFieldSpec("autonomous.universe_min_turnover_24h", "自动循环", "Universe 最低 24h 成交额", "number", 0.0, minimum=0.0),
    ConfigFieldSpec("autonomous.universe_max_spread_pct", "自动循环", "Universe 最大点差百分比", "number", 2.0, minimum=0.0, maximum=100.0),
    ConfigFieldSpec("autonomous.run_operator_workbench", "自动循环", "运行交易建议工作台", "boolean", True),
    ConfigFieldSpec("autonomous.run_ai_debate", "自动循环", "运行 AI 多 Agent 辩论", "boolean", True),
    ConfigFieldSpec("autonomous.workflow_engine_version", "自动循环", "工作流引擎版本", "integer", 2, minimum=1, maximum=2, help="1 使用兼容 Council；2 启用条件、上下文策略和受限循环能力。"),
    ConfigFieldSpec("autonomous.workflow_depth", "自动循环", "默认研究深度", "select", "standard", options=("quick", "standard", "deep")),
    ConfigFieldSpec("autonomous.workflow_director_enabled", "自动循环", "启用 Research Director", "boolean", True, help="按触发类型、问题和产品上下文选择工作流模板与轮次。"),
    ConfigFieldSpec("autonomous.workflow_learning_enabled", "自动循环", "启用工作流学习", "boolean", True, help="只注入可追溯的选择性记忆，不自动修改 Prompt 或发布模板。"),
    ConfigFieldSpec("autonomous.ai_debate_rounds", "自动循环", "AI 辩论轮数", "integer", 3, minimum=2, maximum=8, help="分析角色实际执行的轮数；Chair 合成在所有分析轮次之后单独执行。"),
    ConfigFieldSpec("autonomous.council_template_id", "自动循环", "Council 模板", "string", "product_advisory"),
    ConfigFieldSpec("autonomous.ai_debate_max_candidates", "自动循环", "AI 辩论候选上限", "integer", 3, minimum=1, maximum=20),
    ConfigFieldSpec("autonomous.ai_trade_min_confidence", "自动循环", "AI 交易建议最低置信度", "number", 0.58, minimum=0.0, maximum=1.0),
    ConfigFieldSpec("autonomous.ai_trade_require_research_confirmation", "自动循环", "方向建议必须有研究确认", "boolean", True),
    ConfigFieldSpec("paper_execution.enabled", "模拟交易", "启用模拟执行步骤", "boolean", False, help="开启后自动循环会生成多交易所模拟执行计划；仍需单独开启提交订单。"),
    ConfigFieldSpec("paper_execution.submit_orders", "模拟交易", "提交模拟订单", "boolean", False, help="仅允许 Gate TestNet 与 Bybit Demo，真实盘 host 始终禁止。"),
    ConfigFieldSpec("paper_execution.require_human_review", "模拟交易", "强制人工复核", "boolean", True, help="安全门禁：只有人工批准的最终方向性决策才能进入模拟执行。"),
    ConfigFieldSpec("paper_execution.adapters", "模拟交易", "模拟交易所", "string_list", ("gate_testnet", "bybit_demo"), help="可填写 gate_testnet、bybit_demo；启用项并发且故障隔离。"),
    ConfigFieldSpec("paper_execution.max_orders_per_adapter", "模拟交易", "每家每轮最大订单", "integer", 1, minimum=1, maximum=20),
    ConfigFieldSpec("paper_execution.max_notional_usdt", "模拟交易", "单笔最大名义价值 USDT", "number", 100.0, minimum=1.0, maximum=100000.0),
    ConfigFieldSpec("paper_execution.min_confidence", "模拟交易", "模拟执行最低置信度", "number", 0.70, minimum=0.0, maximum=1.0),
    ConfigFieldSpec("paper_execution.max_workers", "模拟交易", "并发执行线程", "integer", 2, minimum=1, maximum=8),
    ConfigFieldSpec("paper_execution.gate_testnet_api_key", "模拟交易", "Gate TestNet API Key", "secret", None, sensitive=True),
    ConfigFieldSpec("paper_execution.gate_testnet_api_secret", "模拟交易", "Gate TestNet API Secret", "secret", None, sensitive=True),
    ConfigFieldSpec("paper_execution.bybit_demo_api_key", "模拟交易", "Bybit Demo API Key", "secret", None, sensitive=True),
    ConfigFieldSpec("paper_execution.bybit_demo_api_secret", "模拟交易", "Bybit Demo API Secret", "secret", None, sensitive=True),
    ConfigFieldSpec("autonomous.symbols", "自动循环", "建议标的", "string_list", ("BTCUSDT",)),
    ConfigFieldSpec("autonomous.providers", "自动循环", "行情来源", "string_list", ("gate:spot", "gate:perpetual", "bybit:linear"), help="自动循环中的 Mainnet 公共行情来源；默认同步 Gate spot/perpetual 与 Bybit linear。"),
    ConfigFieldSpec("autonomous.intervals", "自动循环", "行情周期", "string_list", ("1h", "4h", "1d")),
    ConfigFieldSpec("autonomous.candle_limit", "自动循环", "K 线数量", "integer", 60, minimum=2, maximum=500),
    ConfigFieldSpec("autonomous.start_bridges", "自动循环", "启动代理桥", "boolean", True),
    ConfigFieldSpec("autonomous.recommendation_min_confidence", "自动循环", "建议最低置信度", "number", 0.0, minimum=0.0, maximum=1.0),
    ConfigFieldSpec("autonomous.max_recommendations", "自动循环", "建议数量上限", "integer", 10, minimum=1, maximum=100),
    ConfigFieldSpec("autonomous.run_recommendation_evaluation", "P1 建议评估", "运行历史建议评估", "boolean", True),
    ConfigFieldSpec("autonomous.evaluation_default_horizon_hours", "P1 建议评估", "默认评估周期小时", "number", 24.0, minimum=1.0, maximum=8760.0),
    ConfigFieldSpec("autonomous.evaluation_max_exit_lag_hours", "P1 建议评估", "到期行情最大延迟小时", "number", 6.0, minimum=0.0, maximum=168.0),
    ConfigFieldSpec("autonomous.evaluation_directional_hit_threshold_pct", "P1 建议评估", "方向命中最低收益百分比", "number", 0.0, minimum=-100.0, maximum=100.0),
    ConfigFieldSpec("autonomous.evaluation_neutral_move_threshold_pct", "P1 建议评估", "中性建议波动阈值百分比", "number", 1.0, minimum=0.0, maximum=100.0),
    ConfigFieldSpec("autonomous.run_portfolio_risk", "P1 组合风险", "运行组合风险分析", "boolean", True),
    ConfigFieldSpec("autonomous.portfolio_min_correlation_samples", "P1 组合风险", "相关性最少对齐样本", "integer", 20, minimum=2, maximum=500),
    ConfigFieldSpec("autonomous.portfolio_correlation_threshold", "P1 组合风险", "强相关阈值", "number", 0.75, minimum=-1.0, maximum=1.0),
    ConfigFieldSpec("autonomous.portfolio_max_single_concentration_pct", "P1 组合风险", "单产品最大集中度百分比", "number", 35.0, minimum=0.0, maximum=100.0),
    ConfigFieldSpec("autonomous.portfolio_max_correlated_cluster_pct", "P1 组合风险", "相关簇最大集中度百分比", "number", 60.0, minimum=0.0, maximum=100.0),
    ConfigFieldSpec("autonomous.portfolio_max_stress_loss_pct", "P1 组合风险", "压力测试最大损失百分比", "number", 10.0, minimum=0.0, maximum=100.0),
    ConfigFieldSpec("autonomous.run_ai_governance", "P1 AI 治理", "运行 AI 治理汇总", "boolean", True),
    ConfigFieldSpec("autonomous.ai_budget_max_total_tokens_per_loop", "P1 AI 治理", "每轮最大 Token", "integer", 500000, minimum=1000, maximum=10000000),
    ConfigFieldSpec("autonomous.ai_budget_max_cost_usd_per_loop", "P1 AI 治理", "每轮最大估算成本 USD", "number", None, help="留空表示不启用 USD 硬预算；配置后仍要求各 AI 站点填写 token 单价。", minimum=0.0, maximum=100000.0),
    ConfigFieldSpec("autonomous.ai_budget_max_output_tokens_per_call", "P1 AI 治理", "单次最大输出 Token", "integer", 4096, minimum=64, maximum=65536),
    ConfigFieldSpec("autonomous.ai_governance_minimum_claim_coverage", "P1 AI 治理", "Claim 最低证据覆盖率", "number", 0.8, minimum=0.0, maximum=1.0),
    ConfigFieldSpec("worker.embedded_scheduler", "常驻 Worker", "Web 内嵌调度器兼容模式", "boolean", False, help="仅用于单进程开发兼容；生产环境保持关闭并独立启动 finbot-worker。"),
    ConfigFieldSpec("worker.poll_seconds", "常驻 Worker", "队列轮询秒数", "number", 2.0, minimum=0.1, maximum=60.0),
    ConfigFieldSpec("worker.lease_seconds", "常驻 Worker", "调度租约秒数", "number", 30.0, minimum=5.0, maximum=600.0),
    ConfigFieldSpec("worker.heartbeat_seconds", "常驻 Worker", "心跳秒数", "number", 5.0, minimum=1.0, maximum=300.0),
    ConfigFieldSpec("macro.fred_api_key", "宏观数据", "FRED API Key", "secret", None, settings_field="fred_api_key", sensitive=True),
    ConfigFieldSpec("macro.bea_api_key", "宏观数据", "BEA API Key", "secret", None, settings_field="bea_api_key", sensitive=True),
    ConfigFieldSpec("macro.alpha_vantage_api_key", "宏观数据", "Alpha Vantage API Key", "secret", None, settings_field="alpha_vantage_api_key", sensitive=True),
    ConfigFieldSpec("macro.openbb_pat", "宏观数据", "OpenBB PAT", "secret", None, settings_field="openbb_pat", sensitive=True),
)

CONFIG_FIELD_MAP = {field.key: field for field in CONFIG_FIELD_SPECS}


class RuntimeConfigStore:
    def __init__(self, project_root: Path, path: Path | None = None):
        self.project_root = project_root
        self.path = path or project_root / "config" / RUNTIME_CONFIG_FILENAME
        self._lock = threading.Lock()

    def payload(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"version": RUNTIME_CONFIG_VERSION, "updated_at": None, "values": {}}
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ValueError(f"运行时配置 JSON 无效：{self.path}: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError(f"运行时配置 JSON 无效：{self.path}: 根节点必须是对象")
        values = payload.get("values") or {}
        if not isinstance(values, dict):
            raise ValueError(f"运行时配置 JSON 无效：{self.path}: values 必须是对象")
        return {
            "version": payload.get("version", RUNTIME_CONFIG_VERSION),
            "updated_at": payload.get("updated_at"),
            "values": values,
        }

    def values(self) -> dict[str, Any]:
        return dict(self.payload()["values"])

    def value(self, key: str, default: Any = None) -> Any:
        values = self.values()
        if key not in values:
            return default
        return normalize_value(CONFIG_FIELD_MAP[key], values[key])

    def update(self, updates: dict[str, Any], clear_keys: list[str] | None = None) -> dict[str, Any]:
        clear = set(clear_keys or [])
        with self._lock:
            payload = self.payload()
            values = dict(payload.get("values") or {})
            for key in clear:
                if key in PROXY_POLICY_KEYS:
                    continue
                _require_runtime_key(key)
                values.pop(key, None)
            for key, value in updates.items():
                if key in PROXY_POLICY_KEYS:
                    continue
                spec = _require_runtime_key(key)
                values[key] = normalize_value(spec, value)
            next_payload = {
                "version": RUNTIME_CONFIG_VERSION,
                "updated_at": _now(),
                "values": values,
            }
            self._write_payload(next_payload)
            return next_payload

    def apply_to_settings(self, settings: Any) -> Any:
        values = self.values()
        updates: dict[str, Any] = {}
        for key, raw_value in values.items():
            spec = CONFIG_FIELD_MAP.get(key)
            if spec is None or spec.settings_field is None:
                continue
            updates[spec.settings_field] = normalize_value(spec, raw_value)
        data_dir_value = values.get("system.data_dir")
        if data_dir_value is not None:
            data_dir = Path(str(normalize_value(CONFIG_FIELD_MAP["system.data_dir"], data_dir_value)))
            updates["data_dir"] = data_dir
            updates["evidence_dir"] = data_dir / "evidence"
            updates["reports_dir"] = data_dir / "reports"
            updates["sqlite_path"] = data_dir / "finbot.sqlite3"
        if "exchange.proxy_runtime_dir" in values:
            updates["proxy_runtime_dir"] = Path(str(normalize_value(CONFIG_FIELD_MAP["exchange.proxy_runtime_dir"], values["exchange.proxy_runtime_dir"])))
        return replace(settings, **updates) if updates else settings

    def snapshot(self, settings: Any, proxy_policy_values: dict[str, Any] | None = None) -> dict[str, Any]:
        payload = self.payload()
        runtime_values = dict(payload.get("values") or {})
        proxy_values = proxy_policy_values or {}
        values = {}
        for spec in CONFIG_FIELD_SPECS:
            if spec.key in PROXY_POLICY_KEYS:
                configured = spec.key in proxy_values
                raw_value = proxy_values.get(spec.key, spec.default)
                source = "proxy_policy" if configured else "default"
            elif spec.key in runtime_values:
                configured = True
                raw_value = normalize_value(spec, runtime_values[spec.key])
                source = "runtime"
            elif spec.settings_field:
                configured = False
                raw_value = getattr(settings, spec.settings_field, spec.default)
                source = "env" if raw_value not in (None, "", spec.default) else "default"
            else:
                configured = False
                raw_value = spec.default
                source = "default"
            values[spec.key] = {
                "value": None if spec.sensitive else _json_value(raw_value),
                "configured": configured or _configured(raw_value),
                "source": source,
                "sensitive": spec.sensitive,
            }
        return {
            "status": "ok",
            "version": RUNTIME_CONFIG_VERSION,
            "runtime_config_path": str(self.path),
            "updated_at": payload.get("updated_at"),
            "schema": [field.to_dict() for field in CONFIG_FIELD_SPECS],
            "values": values,
        }

    def _write_payload(self, payload: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        tmp_path.replace(self.path)


def normalize_value(spec: ConfigFieldSpec, value: Any) -> Any:
    if value is None:
        return None
    if spec.kind in {"string", "secret", "multiline"}:
        return str(value)
    if spec.kind == "select":
        text = str(value).strip()
        if spec.options and text not in spec.options:
            raise ValueError(f"{spec.label} 的取值不支持：{text}")
        return text
    if spec.kind == "boolean":
        return _bool_value(value)
    if spec.kind == "integer":
        if value == "":
            return None
        number = int(value)
        _check_bounds(spec, number)
        return number
    if spec.kind == "number":
        if value == "":
            return None
        number = float(value)
        _check_bounds(spec, number)
        return number
    if spec.kind == "string_list":
        if isinstance(value, str):
            items = [item.strip() for item in value.split(",")]
        elif isinstance(value, (list, tuple)):
            items = [str(item).strip() for item in value]
        else:
            raise ValueError(f"{spec.label} 必须是列表或逗号分隔文本")
        return [item for item in items if item]
    raise ValueError(f"不支持的配置类型：{spec.kind}")


def _require_runtime_key(key: str) -> ConfigFieldSpec:
    spec = CONFIG_FIELD_MAP.get(key)
    if spec is None:
        raise ValueError(f"不支持的配置项：{key}")
    if key in PROXY_POLICY_KEYS:
        raise ValueError(f"代理策略配置项必须写入 proxy_policy：{key}")
    return spec


def _check_bounds(spec: ConfigFieldSpec, value: float) -> None:
    if spec.minimum is not None and value < spec.minimum:
        raise ValueError(f"{spec.label} 不能小于 {spec.minimum}")
    if spec.maximum is not None and value > spec.maximum:
        raise ValueError(f"{spec.label} 不能大于 {spec.maximum}")


def _bool_value(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y", "on", "是", "开启"}
    return False


def _configured(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict, set)):
        return bool(value)
    return True


def _json_value(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, tuple):
        return list(value)
    return value


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
