from __future__ import annotations

import os
from dataclasses import asdict, dataclass
from typing import Any

from finbot.ai.openai_compatible import DEFAULT_PROVIDER_KEYS_FILE
from finbot.config.runtime_config import RuntimeConfigStore


@dataclass(frozen=True)
class AutonomousLoopConfig:
    enabled: bool = False
    interval_minutes: int = 60
    data_dir: str = "data"
    catalog_path: str = "config/source_catalog.example.yml"
    topics_path: str = "config/topic_watchlists.example.yml"
    profile: str = "phase8-autonomous"
    continue_on_error: bool = True
    run_research_pipeline: bool = True
    run_ingestion: bool = True
    max_initial_jobs: int = 20
    run_ai_compression: bool = True
    ai_compression_dry_run: bool = False
    run_followups: bool = True
    followups_dry_run: bool = False
    max_events: int = 10
    include_background_council: bool = True
    run_instrument_catalog: bool = True
    universe_mode: str = "hybrid"
    universe_quote_assets: tuple[str, ...] = ("USDT",)
    universe_max_instruments: int = 12
    universe_min_turnover_24h: float = 0.0
    universe_max_spread_pct: float = 2.0
    run_operator_workbench: bool = True
    run_ai_debate: bool = True
    workflow_engine_version: int = 2
    workflow_depth: str = "standard"
    workflow_director_enabled: bool = True
    workflow_learning_enabled: bool = True
    ai_debate_rounds: int = 3
    council_template_id: str = "product_advisory"
    ai_debate_max_candidates: int = 3
    ai_trade_min_confidence: float = 0.58
    ai_trade_require_research_confirmation: bool = True
    execution_robot_enabled: bool = True
    execution_robot_max_output_tokens: int = 2_048
    paper_execution_enabled: bool = False
    paper_execution_submit_orders: bool = False
    paper_execution_require_human_review: bool = False
    paper_execution_adapters: tuple[str, ...] = ("gate_testnet", "bybit_demo")
    paper_execution_max_orders_per_adapter: int = 1
    paper_execution_max_notional_usdt: float = 100.0
    paper_execution_min_confidence: float = 0.70
    paper_execution_max_workers: int = 2
    gate_testnet_api_key: str = ""
    gate_testnet_api_secret: str = ""
    bybit_demo_api_key: str = ""
    bybit_demo_api_secret: str = ""
    ai_keys_file: str = str(DEFAULT_PROVIDER_KEYS_FILE)
    symbols: tuple[str, ...] = ("BTCUSDT",)
    providers: tuple[str, ...] = ("gate:spot", "gate:perpetual", "bybit:linear")
    intervals: tuple[str, ...] = ("1h", "4h", "1d")
    candle_limit: int = 60
    start_bridges: bool = True
    recommendation_min_confidence: float = 0.0
    max_recommendations: int = 10
    run_recommendation_evaluation: bool = True
    evaluation_default_horizon_hours: float = 24.0
    evaluation_max_exit_lag_hours: float = 6.0
    evaluation_directional_hit_threshold_pct: float = 0.0
    evaluation_neutral_move_threshold_pct: float = 1.0
    run_portfolio_risk: bool = True
    portfolio_min_correlation_samples: int = 20
    portfolio_correlation_threshold: float = 0.75
    portfolio_max_single_concentration_pct: float = 35.0
    portfolio_max_correlated_cluster_pct: float = 60.0
    portfolio_max_stress_loss_pct: float = 10.0
    run_ai_governance: bool = True
    ai_budget_max_total_tokens_per_loop: int = 500_000
    ai_budget_max_cost_usd_per_loop: float | None = None
    ai_budget_max_output_tokens_per_call: int = 4_096
    ai_governance_minimum_claim_coverage: float = 0.8

    @classmethod
    def from_runtime_config(
        cls,
        store: RuntimeConfigStore,
        data_dir: str = "data",
        catalog_path: str = "config/source_catalog.example.yml",
        topics_path: str = "config/topic_watchlists.example.yml",
    ) -> "AutonomousLoopConfig":
        def value(key: str, default: Any) -> Any:
            return store.value(key, default)

        default_providers = value("exchange.enabled_public_providers", cls.providers)

        return cls(
            enabled=bool(value("autonomous.enabled", cls.enabled)),
            interval_minutes=int(value("autonomous.interval_minutes", cls.interval_minutes)),
            data_dir=str(value("system.data_dir", data_dir)),
            catalog_path=str(value("system.catalog_path", catalog_path)),
            topics_path=str(value("system.topics_path", topics_path)),
            continue_on_error=bool(value("autonomous.continue_on_error", cls.continue_on_error)),
            run_research_pipeline=bool(value("autonomous.run_research_pipeline", cls.run_research_pipeline)),
            run_ingestion=bool(value("autonomous.run_ingestion", cls.run_ingestion)),
            max_initial_jobs=int(value("autonomous.max_initial_jobs", cls.max_initial_jobs)),
            run_ai_compression=bool(value("autonomous.run_ai_compression", cls.run_ai_compression)),
            ai_compression_dry_run=bool(
                value("autonomous.ai_compression_dry_run", cls.ai_compression_dry_run)
            ),
            run_followups=bool(value("autonomous.run_followups", cls.run_followups)),
            followups_dry_run=bool(value("autonomous.followups_dry_run", cls.followups_dry_run)),
            max_events=int(value("autonomous.max_events", cls.max_events)),
            include_background_council=bool(
                value("autonomous.include_background_council", cls.include_background_council)
            ),
            run_instrument_catalog=bool(
                value("autonomous.run_instrument_catalog", cls.run_instrument_catalog)
            ),
            universe_mode=str(value("autonomous.universe_mode", cls.universe_mode)),
            universe_quote_assets=tuple(
                _string_list(value("autonomous.universe_quote_assets", cls.universe_quote_assets))
            ),
            universe_max_instruments=int(
                value("autonomous.universe_max_instruments", cls.universe_max_instruments)
            ),
            universe_min_turnover_24h=float(
                value("autonomous.universe_min_turnover_24h", cls.universe_min_turnover_24h)
            ),
            universe_max_spread_pct=float(
                value("autonomous.universe_max_spread_pct", cls.universe_max_spread_pct)
            ),
            run_operator_workbench=bool(
                value("autonomous.run_operator_workbench", cls.run_operator_workbench)
            ),
            run_ai_debate=bool(value("autonomous.run_ai_debate", cls.run_ai_debate)),
            workflow_engine_version=int(
                value("autonomous.workflow_engine_version", cls.workflow_engine_version)
            ),
            workflow_depth=str(value("autonomous.workflow_depth", cls.workflow_depth)),
            workflow_director_enabled=bool(
                value("autonomous.workflow_director_enabled", cls.workflow_director_enabled)
            ),
            workflow_learning_enabled=bool(
                value("autonomous.workflow_learning_enabled", cls.workflow_learning_enabled)
            ),
            ai_debate_rounds=int(value("autonomous.ai_debate_rounds", cls.ai_debate_rounds)),
            council_template_id=str(value("autonomous.council_template_id", cls.council_template_id)),
            ai_debate_max_candidates=int(
                value("autonomous.ai_debate_max_candidates", cls.ai_debate_max_candidates)
            ),
            ai_trade_min_confidence=float(
                value("autonomous.ai_trade_min_confidence", cls.ai_trade_min_confidence)
            ),
            ai_trade_require_research_confirmation=bool(
                value(
                    "autonomous.ai_trade_require_research_confirmation",
                    cls.ai_trade_require_research_confirmation,
                )
            ),
            execution_robot_enabled=bool(
                value("execution_robot.enabled", cls.execution_robot_enabled)
            ),
            execution_robot_max_output_tokens=int(
                value("execution_robot.max_output_tokens", cls.execution_robot_max_output_tokens)
            ),
            paper_execution_enabled=bool(
                value("paper_execution.enabled", cls.paper_execution_enabled)
            ),
            paper_execution_submit_orders=bool(
                value("paper_execution.submit_orders", cls.paper_execution_submit_orders)
            ),
            paper_execution_require_human_review=bool(
                value("paper_execution.require_human_review", cls.paper_execution_require_human_review)
            ),
            paper_execution_adapters=tuple(
                _string_list(value("paper_execution.adapters", cls.paper_execution_adapters))
            ),
            paper_execution_max_orders_per_adapter=int(
                value(
                    "paper_execution.max_orders_per_adapter",
                    cls.paper_execution_max_orders_per_adapter,
                )
            ),
            paper_execution_max_notional_usdt=float(
                value("paper_execution.max_notional_usdt", cls.paper_execution_max_notional_usdt)
            ),
            paper_execution_min_confidence=float(
                value("paper_execution.min_confidence", cls.paper_execution_min_confidence)
            ),
            paper_execution_max_workers=int(
                value("paper_execution.max_workers", cls.paper_execution_max_workers)
            ),
            gate_testnet_api_key=str(
                value("paper_execution.gate_testnet_api_key", os.getenv("GATE_TESTNET_API_KEY", "")) or ""
            ),
            gate_testnet_api_secret=str(
                value("paper_execution.gate_testnet_api_secret", os.getenv("GATE_TESTNET_API_SECRET", "")) or ""
            ),
            bybit_demo_api_key=str(
                value("paper_execution.bybit_demo_api_key", os.getenv("BYBIT_DEMO_API_KEY", "")) or ""
            ),
            bybit_demo_api_secret=str(
                value("paper_execution.bybit_demo_api_secret", os.getenv("BYBIT_DEMO_API_SECRET", "")) or ""
            ),
            ai_keys_file=str(value("ai.keys_file", cls.ai_keys_file)),
            symbols=tuple(_string_list(value("autonomous.symbols", cls.symbols))),
            providers=tuple(_string_list(value("autonomous.providers", default_providers))),
            intervals=tuple(_string_list(value("autonomous.intervals", cls.intervals))),
            candle_limit=int(value("autonomous.candle_limit", cls.candle_limit)),
            start_bridges=bool(value("autonomous.start_bridges", cls.start_bridges)),
            recommendation_min_confidence=float(
                value("autonomous.recommendation_min_confidence", cls.recommendation_min_confidence)
            ),
            max_recommendations=int(value("autonomous.max_recommendations", cls.max_recommendations)),
            run_recommendation_evaluation=bool(
                value("autonomous.run_recommendation_evaluation", cls.run_recommendation_evaluation)
            ),
            evaluation_default_horizon_hours=float(
                value("autonomous.evaluation_default_horizon_hours", cls.evaluation_default_horizon_hours)
            ),
            evaluation_max_exit_lag_hours=float(
                value("autonomous.evaluation_max_exit_lag_hours", cls.evaluation_max_exit_lag_hours)
            ),
            evaluation_directional_hit_threshold_pct=float(
                value(
                    "autonomous.evaluation_directional_hit_threshold_pct",
                    cls.evaluation_directional_hit_threshold_pct,
                )
            ),
            evaluation_neutral_move_threshold_pct=float(
                value(
                    "autonomous.evaluation_neutral_move_threshold_pct",
                    cls.evaluation_neutral_move_threshold_pct,
                )
            ),
            run_portfolio_risk=bool(value("autonomous.run_portfolio_risk", cls.run_portfolio_risk)),
            portfolio_min_correlation_samples=int(
                value("autonomous.portfolio_min_correlation_samples", cls.portfolio_min_correlation_samples)
            ),
            portfolio_correlation_threshold=float(
                value("autonomous.portfolio_correlation_threshold", cls.portfolio_correlation_threshold)
            ),
            portfolio_max_single_concentration_pct=float(
                value(
                    "autonomous.portfolio_max_single_concentration_pct",
                    cls.portfolio_max_single_concentration_pct,
                )
            ),
            portfolio_max_correlated_cluster_pct=float(
                value(
                    "autonomous.portfolio_max_correlated_cluster_pct",
                    cls.portfolio_max_correlated_cluster_pct,
                )
            ),
            portfolio_max_stress_loss_pct=float(
                value("autonomous.portfolio_max_stress_loss_pct", cls.portfolio_max_stress_loss_pct)
            ),
            run_ai_governance=bool(value("autonomous.run_ai_governance", cls.run_ai_governance)),
            ai_budget_max_total_tokens_per_loop=int(
                value(
                    "autonomous.ai_budget_max_total_tokens_per_loop",
                    cls.ai_budget_max_total_tokens_per_loop,
                )
            ),
            ai_budget_max_cost_usd_per_loop=_optional_float(
                value(
                    "autonomous.ai_budget_max_cost_usd_per_loop",
                    cls.ai_budget_max_cost_usd_per_loop,
                )
            ),
            ai_budget_max_output_tokens_per_call=int(
                value(
                    "autonomous.ai_budget_max_output_tokens_per_call",
                    cls.ai_budget_max_output_tokens_per_call,
                )
            ),
            ai_governance_minimum_claim_coverage=float(
                value(
                    "autonomous.ai_governance_minimum_claim_coverage",
                    cls.ai_governance_minimum_claim_coverage,
                )
            ),
        )

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        gate_key = str(payload.pop("gate_testnet_api_key", "") or "")
        gate_secret = str(payload.pop("gate_testnet_api_secret", "") or "")
        bybit_key = str(payload.pop("bybit_demo_api_key", "") or "")
        bybit_secret = str(payload.pop("bybit_demo_api_secret", "") or "")
        credentials = {
            "gate_testnet": bool(gate_key and gate_secret),
            "bybit_demo": bool(bybit_key and bybit_secret),
        }
        payload["symbols"] = list(self.symbols)
        payload["providers"] = list(self.providers)
        payload["intervals"] = list(self.intervals)
        payload["universe_quote_assets"] = list(self.universe_quote_assets)
        payload["paper_execution_adapters"] = list(self.paper_execution_adapters)
        payload["paper_execution_credentials_configured"] = credentials
        return payload


def _string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    if isinstance(value, (list, tuple)):
        return [str(item).strip() for item in value if str(item).strip()]
    return [str(value).strip()] if str(value).strip() else []


def _optional_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
