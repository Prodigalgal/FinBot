from __future__ import annotations

import asyncio
import hashlib
import json
import traceback
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from time import perf_counter
from typing import Any

from finbot.ai.governance import AIGovernanceConfig, AIGovernanceReporter
from finbot.ai.openai_compatible import load_provider_configs
from finbot.advisory.engine import AdvisoryConfig
from finbot.autonomous.ai_debate import AIDebateConfig, AIDebateCouncilRunner
from finbot.autonomous.config import AutonomousLoopConfig
from finbot.autonomous.execution_robot import ExecutionRobot, ExecutionRobotConfig
from finbot.autonomous.product_candidates import ProductCandidateBuilder, ProductCandidateConfig
from finbot.autonomous.product_selector import ProductRecommendationSelector, ProductSelectionConfig
from finbot.cli.common import build_store, write_report
from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.paths import runtime_root
from finbot.council.director import ResearchDirector
from finbot.evaluation.recommendations import RecommendationEvaluationConfig, RecommendationEvaluator
from finbot.exchange.runtime import PAPER_ADAPTER_PROVIDERS, execute_paper_decisions
from finbot.instruments.catalog import InstrumentCatalogSynchronizer
from finbot.instruments.models import InstrumentMarket
from finbot.instruments.product_center import ProductCatalogService
from finbot.instruments.universe import HybridUniverseBuilder, UniverseConfig
from finbot.market.public_exchanges import PublicExchangeMarketDataClient
from finbot.network.proxy_runtime import ProxyRuntime
from finbot.operator.workbench import (
    InstrumentSelection,
    OperatorWorkbenchBuilder,
    OperatorWorkbenchConfig,
    parse_intervals,
    parse_provider_specs,
)
from finbot.orchestration.pipeline import ResearchPipelineConfig, build_pipeline_runner, provider_order_from_env
from finbot.risk.portfolio import PortfolioRiskAnalyzer, PortfolioRiskConfig


AUTONOMOUS_LOOP_STEPS = (
    "research_pipeline",
    "instrument_catalog",
    "universe_selection",
    "operator_workbench",
    "product_candidates",
    "ai_debate",
    "trade_synthesis",
    "product_selection",
    "recommendation_evaluation",
    "portfolio_risk",
    "execution_robot",
    "ai_governance",
    "paper_execution",
    "publish_status",
)

StepExecutor = Callable[[AutonomousLoopConfig, str], dict[str, Any]]


class AutonomousResearchLoopRunner:
    def __init__(
        self,
        research_executor: StepExecutor | None = None,
        catalog_executor: StepExecutor | None = None,
        universe_executor: StepExecutor | None = None,
        operator_executor: StepExecutor | None = None,
        selector: ProductRecommendationSelector | None = None,
        candidate_builder: ProductCandidateBuilder | None = None,
        debate_runner_factory: Callable[[Any], AIDebateCouncilRunner] | None = None,
        execution_robot_factory: Callable[[Any], ExecutionRobot] | None = None,
    ):
        self.research_executor = research_executor
        self.catalog_executor = catalog_executor
        self.universe_executor = universe_executor
        self.operator_executor = operator_executor
        self.selector = selector or ProductRecommendationSelector()
        self.candidate_builder = candidate_builder or ProductCandidateBuilder()
        self.debate_runner_factory = debate_runner_factory
        self.execution_robot_factory = execution_robot_factory

    def run(
        self,
        config: AutonomousLoopConfig,
        trigger_type: str = "manual",
        request_context: dict[str, Any] | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        started_at = _now()
        safe_request_context = _safe_request_context(request_context)
        loop_run_id = _loop_run_id(config, trigger_type, started_at, safe_request_context)
        stored_config = config.to_dict()
        if safe_request_context:
            stored_config["request_context"] = safe_request_context
        store.insert_autonomous_loop_run(
            {
                "loop_run_id": loop_run_id,
                "status": "running",
                "trigger_type": trigger_type,
                "config": stored_config,
                "summary": {"steps": list(AUTONOMOUS_LOOP_STEPS)},
                "started_at": started_at,
            }
        )
        if request_id:
            store.link_autonomous_request_loop(request_id, loop_run_id)
        context: dict[str, Any] = {"request_context": safe_request_context, "trigger_type": trigger_type}
        step_reports: list[dict[str, Any]] = []
        for step_name in AUTONOMOUS_LOOP_STEPS:
            if not self._step_enabled(step_name, config):
                report = self._skip_step(store, loop_run_id, step_name, config, context, "配置关闭")
            else:
                report = self._execute_step(store, loop_run_id, step_name, config, context)
            step_reports.append(report)
            if report["status"] == "failed" and not config.continue_on_error:
                break

        finished_at = _now()
        decision_readiness = _decision_readiness(context)
        summary = self._summary(loop_run_id, step_reports, context, decision_readiness)
        if safe_request_context.get("query"):
            summary["request_query"] = safe_request_context["query"]
        status = self._status(step_reports)
        store.update_autonomous_loop_run(
            loop_run_id=loop_run_id,
            status=status,
            summary=summary,
            finished_at=finished_at,
            error=summary.get("first_error"),
        )
        report = {
            "status": status,
            "loop_run_id": loop_run_id,
            "trigger_type": trigger_type,
            "started_at": started_at,
            "finished_at": finished_at,
            "summary": summary,
            "steps": step_reports,
            "config": stored_config,
            "request_context": safe_request_context,
            "decision_readiness": decision_readiness,
            "recommended_products": context.get("recommended_products", []),
            "policy": {
                "execution_allowed": False,
                "order_api_allowed": False,
                "private_exchange_api_allowed": False,
                "human_confirmation_required": True,
            },
        }
        output = write_report(settings, "autonomous-loop-latest.json", report)
        report["output"] = str(output)
        return report

    def _step_enabled(self, step_name: str, config: AutonomousLoopConfig) -> bool:
        if step_name == "research_pipeline":
            return config.run_research_pipeline
        if step_name == "instrument_catalog":
            return config.run_instrument_catalog
        if step_name == "universe_selection":
            return config.run_operator_workbench
        if step_name == "operator_workbench":
            return config.run_operator_workbench
        if step_name == "product_candidates":
            return config.run_operator_workbench
        if step_name in {"ai_debate", "trade_synthesis"}:
            return config.run_ai_debate and config.run_operator_workbench
        if step_name == "product_selection":
            return config.run_operator_workbench
        if step_name == "recommendation_evaluation":
            return config.run_recommendation_evaluation
        if step_name == "portfolio_risk":
            return config.run_portfolio_risk and config.run_operator_workbench
        if step_name == "ai_governance":
            return config.run_ai_governance and config.run_ai_debate
        if step_name == "execution_robot":
            return config.execution_robot_enabled and config.run_ai_debate
        if step_name == "paper_execution":
            return config.paper_execution_enabled
        return True

    def _execute_step(
        self,
        store: Any,
        loop_run_id: str,
        step_name: str,
        config: AutonomousLoopConfig,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        started_at = _now()
        attempt = store.next_autonomous_loop_step_attempt(loop_run_id, step_name)
        step_id = _step_id(loop_run_id, step_name, attempt)
        store.insert_autonomous_loop_step(
            {
                "step_id": step_id,
                "loop_run_id": loop_run_id,
                "step_name": step_name,
                "status": "running",
                "attempt": attempt,
                "started_at": started_at,
                "input": self._step_input(step_name, config, context),
                "output": {},
                "created_at": started_at,
                "updated_at": started_at,
            }
        )
        timer = perf_counter()
        try:
            output = self._call_step(step_name, config, loop_run_id, context)
            finished_at = _now()
            duration_ms = int((perf_counter() - timer) * 1000)
            compact_output = _compact_output(output)
            if _failed_output(output):
                error = str(output.get("error") or output.get("reason") or f"{step_name} reported failed")
                store.update_autonomous_loop_step(
                    step_id=step_id,
                    status="failed",
                    output=compact_output,
                    finished_at=finished_at,
                    duration_ms=duration_ms,
                    error=error,
                )
                return _step_report(
                    step_id,
                    step_name,
                    "failed",
                    attempt,
                    started_at,
                    finished_at,
                    duration_ms,
                    compact_output,
                    error,
                )
            store.update_autonomous_loop_step(
                step_id=step_id,
                status="passed",
                output=compact_output,
                finished_at=finished_at,
                duration_ms=duration_ms,
            )
            store.insert_autonomous_loop_artifact(
                {
                    "artifact_id": _artifact_id(loop_run_id, step_name, finished_at),
                    "loop_run_id": loop_run_id,
                    "step_name": step_name,
                    "artifact_type": "step-output",
                    "ref_id": _artifact_ref(output),
                    "payload": compact_output,
                    "created_at": finished_at,
                }
            )
            return _step_report(step_id, step_name, "passed", attempt, started_at, finished_at, duration_ms, compact_output)
        except Exception as exc:
            finished_at = _now()
            duration_ms = int((perf_counter() - timer) * 1000)
            error = f"{type(exc).__name__}: {exc}"
            output = {"error": error, "traceback": traceback.format_exc(limit=8)}
            store.update_autonomous_loop_step(
                step_id=step_id,
                status="failed",
                output=output,
                finished_at=finished_at,
                duration_ms=duration_ms,
                error=error,
            )
            return _step_report(step_id, step_name, "failed", attempt, started_at, finished_at, duration_ms, output, error)

    def _skip_step(
        self,
        store: Any,
        loop_run_id: str,
        step_name: str,
        config: AutonomousLoopConfig,
        context: dict[str, Any],
        reason: str,
    ) -> dict[str, Any]:
        created_at = _now()
        attempt = store.next_autonomous_loop_step_attempt(loop_run_id, step_name)
        step_id = _step_id(loop_run_id, step_name, attempt)
        output = {"reason": reason}
        store.insert_autonomous_loop_step(
            {
                "step_id": step_id,
                "loop_run_id": loop_run_id,
                "step_name": step_name,
                "status": "skipped",
                "attempt": attempt,
                "started_at": created_at,
                "finished_at": created_at,
                "duration_ms": 0,
                "input": self._step_input(step_name, config, context),
                "output": output,
                "created_at": created_at,
                "updated_at": created_at,
            }
        )
        return _step_report(step_id, step_name, "skipped", attempt, created_at, created_at, 0, output)

    def _call_step(
        self,
        step_name: str,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        if step_name == "research_pipeline":
            report = (
                self.research_executor(config, loop_run_id)
                if self.research_executor
                else self._run_research_pipeline(config, loop_run_id, context)
            )
            context["research_report"] = report
            return report
        if step_name == "instrument_catalog":
            report = (
                self.catalog_executor(config, loop_run_id)
                if self.catalog_executor
                else self._run_instrument_catalog(config, loop_run_id)
            )
            context["instrument_catalog"] = report
            return report
        if step_name == "universe_selection":
            report = (
                self.universe_executor(config, loop_run_id)
                if self.universe_executor
                else self._run_universe_selection(config, loop_run_id, context)
            )
            context["universe"] = report
            return report
        if step_name == "operator_workbench":
            report = (
                self.operator_executor(config, loop_run_id)
                if self.operator_executor
                else self._run_operator_workbench(config, loop_run_id, context)
            )
            context["operator_report"] = report
            return report
        if step_name == "product_candidates":
            universe_symbols = tuple(
                str(instrument.get("normalized_symbol") or instrument.get("symbol"))
                for instrument in (context.get("universe") or {}).get("instruments", [])
                if isinstance(instrument, dict) and (instrument.get("normalized_symbol") or instrument.get("symbol"))
            )
            candidates = self.candidate_builder.build(
                context.get("operator_report"),
                ProductCandidateConfig(
                    symbols=universe_symbols or config.symbols,
                    limit=max(config.ai_debate_max_candidates, config.max_recommendations),
                ),
            )
            context["product_candidates"] = candidates
            return candidates
        if step_name == "ai_debate":
            debate = self._run_ai_debate(config, loop_run_id, context)
            context["ai_debate"] = debate
            return debate
        if step_name == "trade_synthesis":
            synthesis = self._run_trade_synthesis(config, loop_run_id, context)
            context["trade_synthesis"] = synthesis
            context["ai_decisions"] = synthesis.get("ai_decisions", [])
            return synthesis
        if step_name == "product_selection":
            selection = self.selector.select(
                context.get("operator_report"),
                ProductSelectionConfig(
                    min_confidence=config.recommendation_min_confidence,
                    limit=config.max_recommendations,
                ),
                ai_decisions=context.get("ai_decisions", []),
            )
            context["recommended_products"] = selection["recommended_products"]
            return selection
        if step_name == "recommendation_evaluation":
            report = self._run_recommendation_evaluation(config, loop_run_id)
            context["recommendation_evaluation"] = report
            return report
        if step_name == "portfolio_risk":
            report = self._run_portfolio_risk(config, loop_run_id, context)
            context["portfolio_risk"] = report
            return report
        if step_name == "execution_robot":
            report = self._run_execution_robot(config, loop_run_id, context)
            context["execution_robot"] = report
            context["execution_decisions"] = report.get("approved_decisions", [])
            return report
        if step_name == "ai_governance":
            report = self._run_ai_governance(config, loop_run_id, context)
            context["ai_governance"] = report
            return report
        if step_name == "paper_execution":
            report = self._run_paper_execution(config, loop_run_id, context)
            context["paper_execution"] = report
            return report
        if step_name == "publish_status":
            return self._publish_status(config, loop_run_id, context)
        raise ValueError(f"不支持的自动循环步骤：{step_name}")

    def _run_research_pipeline(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        settings, runner = build_pipeline_runner(
            data_dir=config.data_dir,
            catalog_path=config.catalog_path,
            topics_path=config.topics_path,
        )
        request_context = context.get("request_context") if isinstance(context.get("request_context"), dict) else {}
        focus_queries = tuple(
            str(value).strip()
            for value in request_context.get("focus_queries", [])
            if str(value).strip()
        )
        source_ids = ("search_firecrawl_global",) if request_context.get("mode") == "instant-research" else ()
        pipeline_config = ResearchPipelineConfig(
            profile=config.profile,
            triggered_by=f"autonomous:{loop_run_id}",
            dry_run=False,
            continue_on_error=config.continue_on_error,
            catalog_path=config.catalog_path,
            topics_path=config.topics_path,
            run_ingestion=config.run_ingestion,
            source_ids=source_ids,
            focus_queries=focus_queries,
            max_initial_jobs=config.max_initial_jobs,
            max_ingestion_followup_jobs=10,
            run_ai_compression=config.run_ai_compression,
            ai_compression_dry_run=config.ai_compression_dry_run,
            ai_provider_order=provider_order_from_env(),
            max_events=config.max_events,
            run_followups=config.run_followups,
            followups_dry_run=config.followups_dry_run,
            rebuild_after_followups=config.run_followups,
            include_watch_only=True,
            phase3_time_window="phase8-autonomous",
            phase4_time_window="phase8-autonomous",
            phase41_time_window="phase8-autonomous",
            phase4_limit_items=max(1, config.max_events * 2),
            phase41_limit_items=max(1, config.max_events * 2),
            include_background_council=config.include_background_council,
            artifact_retention_keep_runs=50,
        )
        report = asyncio.run(runner.run(pipeline_config))
        output = write_report(settings, "research-pipeline-latest.json", report)
        return {
            "status": report.get("status"),
            "run_id": report.get("run_id"),
            "focus_query": request_context.get("query"),
            "summary": report.get("summary", {}),
            "steps": report.get("steps") or report.get("current_attempt_steps") or [],
            "output": str(output),
        }

    def _run_instrument_catalog(self, config: AutonomousLoopConfig, loop_run_id: str) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        proxy_runtime = ProxyRuntime.from_settings(settings, start_bridges=config.start_bridges)
        try:
            market_client = PublicExchangeMarketDataClient(
                timeout_seconds=20.0,
                user_agent=settings.http_user_agent,
                proxy_router=proxy_runtime.router,
            )
            synchronizer = InstrumentCatalogSynchronizer(store=store, client=market_client)
            markets = tuple(InstrumentMarket.parse(value) for value in config.providers)
            report = asyncio.run(synchronizer.sync(markets))
            report["autonomous_loop_run_id"] = loop_run_id
            report["proxy_runtime"] = proxy_runtime.summary()
            output = write_report(settings, "instrument-catalog-latest.json", report)
            report["output"] = str(output)
            return report
        finally:
            proxy_runtime.close()

    def _run_universe_selection(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        markets = tuple(InstrumentMarket.parse(value) for value in config.providers)
        research_assets = _research_assets(store, context)
        watchlist_sources = ProductCatalogService(store).universe_instrument_ids()
        report = HybridUniverseBuilder(store).build(
            UniverseConfig(
                mode=config.universe_mode,
                watchlist=config.symbols,
                watchlist_instrument_ids=watchlist_sources["research"],
                pinned_instrument_ids=watchlist_sources["pinned"],
                markets=markets,
                quote_assets=config.universe_quote_assets,
                max_instruments=config.universe_max_instruments,
                max_spread_pct=config.universe_max_spread_pct,
                min_turnover_24h=config.universe_min_turnover_24h,
            ),
            loop_run_id=loop_run_id,
            research_assets=research_assets,
        )
        output = write_report(settings, "universe-latest.json", report)
        report["output"] = str(output)
        return report

    def _run_operator_workbench(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        universe_instruments = [
            instrument
            for instrument in (context.get("universe") or {}).get("instruments", [])
            if isinstance(instrument, dict)
        ]
        if not universe_instruments:
            return {
                "status": "empty",
                "autonomous_loop_run_id": loop_run_id,
                "summary": {"advice_count": 0, "reason": "Hybrid Universe selected no verified instruments."},
                "items": [],
                "policy": {
                    "execution_allowed": False,
                    "private_exchange_api_allowed": False,
                },
            }
        proxy_runtime = ProxyRuntime.from_settings(settings, start_bridges=config.start_bridges)
        try:
            market_client = PublicExchangeMarketDataClient(
                timeout_seconds=20.0,
                user_agent=settings.http_user_agent,
                proxy_router=proxy_runtime.router,
            )
            builder = OperatorWorkbenchBuilder(store=store, market_client=market_client)
            report = asyncio.run(
                builder.build(
                    OperatorWorkbenchConfig(
                        symbols=tuple(str(instrument["symbol"]) for instrument in universe_instruments),
                        providers=parse_provider_specs(config.providers),
                        instruments=tuple(
                            InstrumentSelection(
                                provider=str(instrument["provider"]),
                                market_type=str(instrument["market_type"]),
                                symbol=str(instrument["symbol"]),
                                instrument_id=str(instrument["instrument_id"]),
                                normalized_symbol=str(instrument.get("normalized_symbol") or instrument["symbol"]),
                                base_asset=str(instrument.get("base_asset") or "") or None,
                            )
                            for instrument in universe_instruments
                        ),
                        data_source="live_public",
                        execution_mode="advisory_only",
                        intervals=parse_intervals(config.intervals),
                        candle_limit=config.candle_limit,
                        persist=True,
                        include_research_context=True,
                        advisory=AdvisoryConfig(profile=config.profile),
                    )
                )
            )
            report["autonomous_loop_run_id"] = loop_run_id
            report["proxy_runtime"] = proxy_runtime.summary()
            output = write_report(settings, "operator-workbench-latest.json", report)
            report["output"] = str(output)
            return report
        finally:
            proxy_runtime.close()

    def _run_ai_debate(self, config: AutonomousLoopConfig, loop_run_id: str, context: dict[str, Any]) -> dict[str, Any]:
        _, store = build_store(config.data_dir)
        runner = self._debate_runner(store, config)
        debate_config = self._debate_config(config, loop_run_id, context)
        return runner.run_debate(context.get("product_candidates") or {}, debate_config)

    def _run_trade_synthesis(self, config: AutonomousLoopConfig, loop_run_id: str, context: dict[str, Any]) -> dict[str, Any]:
        _, store = build_store(config.data_dir)
        runner = self._debate_runner(store, config)
        debate_config = self._debate_config(config, loop_run_id, context)
        return runner.synthesize_decisions(
            context.get("ai_debate") or {},
            context.get("product_candidates") or {},
            debate_config,
        )

    def _run_recommendation_evaluation(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
    ) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        intervals = config.intervals or ("1h",)
        interval = "1h" if "1h" in intervals else intervals[0]
        report = RecommendationEvaluator(store).evaluate(
            loop_run_id=loop_run_id,
            config=RecommendationEvaluationConfig(
                default_horizon_hours=config.evaluation_default_horizon_hours,
                candle_interval=interval,
                max_exit_lag_hours=config.evaluation_max_exit_lag_hours,
                directional_hit_threshold_pct=config.evaluation_directional_hit_threshold_pct,
                neutral_move_threshold_pct=config.evaluation_neutral_move_threshold_pct,
            ),
        )
        report["output"] = str(write_report(settings, "recommendation-evaluation-latest.json", report))
        return report

    def _run_portfolio_risk(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        intervals = config.intervals or ("1h",)
        interval = "1h" if "1h" in intervals else intervals[0]
        report = PortfolioRiskAnalyzer(store).analyze(
            loop_run_id=loop_run_id,
            recommendations=[
                item for item in context.get("recommended_products", [])
                if isinstance(item, dict)
            ],
            config=PortfolioRiskConfig(
                candle_interval=interval,
                lookback_points=max(2, config.candle_limit),
                min_correlation_samples=config.portfolio_min_correlation_samples,
                correlation_threshold=config.portfolio_correlation_threshold,
                execution_providers=tuple(
                    dict.fromkeys(
                        PAPER_ADAPTER_PROVIDERS[adapter_id]
                        for adapter_id in config.paper_execution_adapters
                        if adapter_id in PAPER_ADAPTER_PROVIDERS
                    )
                ),
                max_single_product_concentration_pct=config.portfolio_max_single_concentration_pct,
                max_correlated_cluster_concentration_pct=config.portfolio_max_correlated_cluster_pct,
                max_hypothetical_stress_loss_pct=config.portfolio_max_stress_loss_pct,
            ),
        )
        report["output"] = str(write_report(settings, "portfolio-risk-latest.json", report))
        return report

    def _run_ai_governance(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        report = AIGovernanceReporter(store, AISitesConfigStore(runtime_root(Path.cwd()))).build(
            loop_run_id=loop_run_id,
            debate_id=(context.get("ai_debate") or {}).get("debate_id"),
            config=AIGovernanceConfig(
                max_total_tokens_per_loop=config.ai_budget_max_total_tokens_per_loop,
                max_cost_usd_per_loop=config.ai_budget_max_cost_usd_per_loop,
                minimum_claim_evidence_coverage=config.ai_governance_minimum_claim_coverage,
            ),
        )
        report["output"] = str(write_report(settings, "ai-governance-latest.json", report))
        return report

    def _run_execution_robot(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        _, store = build_store(config.data_dir)
        robot = (
            self.execution_robot_factory(store)
            if self.execution_robot_factory
            else ExecutionRobot(
                store=store,
                providers=load_provider_configs(keys_file=Path(config.ai_keys_file), project_root=Path.cwd()),
                ai_store=AISitesConfigStore(runtime_root(Path.cwd())),
            )
        )
        return robot.run(
            decisions=[item for item in context.get("ai_decisions", []) if isinstance(item, dict)],
            portfolio_risk=context.get("portfolio_risk"),
            config=ExecutionRobotConfig(
                loop_run_id=loop_run_id,
                debate_id=(context.get("ai_debate") or {}).get("debate_id"),
                max_total_tokens_per_loop=config.ai_budget_max_total_tokens_per_loop,
                max_cost_usd_per_loop=config.ai_budget_max_cost_usd_per_loop,
                max_output_tokens=min(
                    config.execution_robot_max_output_tokens,
                    config.ai_budget_max_output_tokens_per_call,
                ),
            ),
        )

    def _run_paper_execution(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        if config.execution_robot_enabled:
            robot = context.get("execution_robot") or {}
            if robot.get("status") != "passed":
                return {
                    "status": "blocked",
                    "execution_run_id": None,
                    "summary": {
                        "execution_count": 0,
                        "reasons": ["执行机器人未通过，按 fail-closed 禁止模拟下单"],
                    },
                    "executions": [],
                }
            decisions = [item for item in context.get("execution_decisions", []) if isinstance(item, dict)]
        else:
            decisions = [item for item in context.get("ai_decisions", []) if isinstance(item, dict)]
        return execute_paper_decisions(
            config=config,
            loop_run_id=loop_run_id,
            decisions=decisions,
            portfolio_risk=context.get("portfolio_risk"),
            ai_governance=context.get("ai_governance"),
        )

    def _debate_runner(self, store: Any, config: AutonomousLoopConfig) -> AIDebateCouncilRunner:
        if self.debate_runner_factory:
            return self.debate_runner_factory(store)
        providers = load_provider_configs(keys_file=Path(config.ai_keys_file), project_root=Path.cwd())
        return AIDebateCouncilRunner(
            store=store,
            providers=providers,
            ai_store=AISitesConfigStore(runtime_root(Path.cwd())),
        )

    def _debate_config(
        self,
        config: AutonomousLoopConfig,
        loop_run_id: str,
        context: dict[str, Any],
    ) -> AIDebateConfig:
        request_context = context.get("request_context") if isinstance(context.get("request_context"), dict) else {}
        director_plan = self._director_plan(config, context)
        return AIDebateConfig(
            loop_run_id=loop_run_id,
            research_pipeline_run_id=(context.get("research_report") or {}).get("run_id"),
            operator_report_id=(context.get("operator_report") or {}).get("report_id"),
            user_query=str(request_context.get("query") or "") or None,
            rounds=int(director_plan["rounds"]) if director_plan else config.ai_debate_rounds,
            template_id=str(director_plan["template_id"]) if director_plan else config.council_template_id,
            max_candidates=config.ai_debate_max_candidates,
            min_confidence=config.ai_trade_min_confidence,
            require_research_confirmation=config.ai_trade_require_research_confirmation,
            max_total_tokens_per_loop=config.ai_budget_max_total_tokens_per_loop,
            max_cost_usd_per_loop=config.ai_budget_max_cost_usd_per_loop,
            max_output_tokens_per_call=config.ai_budget_max_output_tokens_per_call,
            director_plan=director_plan,
            learning_enabled=config.workflow_learning_enabled,
        )

    @staticmethod
    def _director_plan(config: AutonomousLoopConfig, context: dict[str, Any]) -> dict[str, Any] | None:
        if config.workflow_engine_version < 2 or not config.workflow_director_enabled:
            return None
        request_context = context.get("request_context") if isinstance(context.get("request_context"), dict) else {}
        candidates_report = context.get("product_candidates") if isinstance(context.get("product_candidates"), dict) else {}
        candidates = candidates_report.get("candidates") if isinstance(candidates_report.get("candidates"), list) else []
        candidate = next((item for item in candidates if isinstance(item, dict)), {})
        research_context = candidate.get("research_context") if isinstance(candidate.get("research_context"), dict) else {}
        request = {
            "trigger_type": str(context.get("trigger_type") or request_context.get("mode") or "autonomous"),
            "query": request_context.get("query"),
            "depth": config.workflow_depth,
            "product_id": candidate.get("product_id") or candidate.get("candidate_id"),
            "symbol": candidate.get("normalized_symbol") or candidate.get("symbol"),
            "product_type": candidate.get("product_type"),
            "market_type": candidate.get("market_type"),
            "evidence_status": research_context.get("status"),
        }
        ai_store = AISitesConfigStore(runtime_root(Path.cwd()))
        return ResearchDirector().plan(request, ai_store.council_templates())

    def _publish_status(self, config: AutonomousLoopConfig, loop_run_id: str, context: dict[str, Any]) -> dict[str, Any]:
        settings, store = build_store(config.data_dir)
        with store.connect() as conn:
            counts = {
                "raw_evidence": conn.execute("select count(*) from raw_evidence").fetchone()[0],
                "event_candidates": conn.execute("select count(*) from event_candidates").fetchone()[0],
                "research_cards": conn.execute("select count(*) from research_cards").fetchone()[0],
                "research_councils": conn.execute("select count(*) from research_councils").fetchone()[0],
                "venue_instruments": conn.execute("select count(*) from venue_instruments").fetchone()[0],
                "universe_runs": conn.execute("select count(*) from universe_runs").fetchone()[0],
                "market_quotes": conn.execute("select count(*) from market_quotes").fetchone()[0],
                "advisory_reports": conn.execute("select count(*) from advisory_reports").fetchone()[0],
                "ai_debate_councils": conn.execute("select count(*) from ai_debate_councils").fetchone()[0],
                "ai_trade_decisions": conn.execute("select count(*) from ai_trade_decisions").fetchone()[0],
                "recommendation_outcomes": conn.execute("select count(*) from recommendation_outcomes").fetchone()[0],
                "portfolio_risk_reports": conn.execute("select count(*) from portfolio_risk_reports").fetchone()[0],
                "ai_invocations": conn.execute("select count(*) from ai_invocations").fetchone()[0],
                "ai_governance_reports": conn.execute("select count(*) from ai_governance_reports").fetchone()[0],
                "paper_executions": conn.execute("select count(*) from paper_executions").fetchone()[0],
            }
        payload = {
            "loop_run_id": loop_run_id,
            "generated_at": _now(),
            "counts": counts,
            "research_run_id": (context.get("research_report") or {}).get("run_id"),
            "universe_run_id": (context.get("universe") or {}).get("universe_run_id"),
            "operator_report_id": (context.get("operator_report") or {}).get("report_id"),
            "ai_debate_id": (context.get("ai_debate") or {}).get("debate_id"),
            "ai_decision_count": len(context.get("ai_decisions", [])),
            "recommended_products_count": len(context.get("recommended_products", [])),
            "evaluation_run_id": (context.get("recommendation_evaluation") or {}).get("evaluation_run_id"),
            "portfolio_risk_report_id": (context.get("portfolio_risk") or {}).get("risk_report_id"),
            "portfolio_risk_status": ((context.get("portfolio_risk") or {}).get("summary") or {}).get("risk_status"),
            "ai_governance_report_id": (context.get("ai_governance") or {}).get("governance_report_id"),
            "ai_governance_status": ((context.get("ai_governance") or {}).get("summary") or {}).get("governance_status"),
            "paper_execution_run_id": (context.get("paper_execution") or {}).get("execution_run_id"),
            "paper_execution_status": (context.get("paper_execution") or {}).get("status"),
            "decision_readiness": _decision_readiness(context),
            "enabled": config.enabled,
            "interval_minutes": config.interval_minutes,
        }
        write_report(settings, "autonomous-status-latest.json", payload)
        return payload

    def _step_input(
        self,
        step_name: str,
        config: AutonomousLoopConfig,
        context: dict[str, Any],
    ) -> dict[str, Any]:
        request_context = context.get("request_context") if isinstance(context.get("request_context"), dict) else {}
        if step_name == "research_pipeline":
            return {
                "run_ingestion": config.run_ingestion,
                "run_ai_compression": config.run_ai_compression,
                "run_followups": config.run_followups,
                "max_events": config.max_events,
                "focus_query": request_context.get("query"),
            }
        if step_name == "instrument_catalog":
            return {"providers": list(config.providers)}
        if step_name == "universe_selection":
            return {
                "mode": config.universe_mode,
                "watchlist": list(config.symbols),
                "providers": list(config.providers),
                "quote_assets": list(config.universe_quote_assets),
                "max_instruments": config.universe_max_instruments,
            }
        if step_name == "operator_workbench":
            return {
                "symbols": list(config.symbols),
                "providers": list(config.providers),
                "intervals": list(config.intervals),
                "candle_limit": config.candle_limit,
            }
        if step_name == "product_candidates":
            return {
                "symbols": list(config.symbols),
                "limit": max(config.ai_debate_max_candidates, config.max_recommendations),
            }
        if step_name == "ai_debate":
            return {
                "rounds": config.ai_debate_rounds,
                "max_candidates": config.ai_debate_max_candidates,
                "user_query": request_context.get("query"),
                "workflow_engine_version": config.workflow_engine_version,
                "workflow_depth": config.workflow_depth,
                "director_enabled": config.workflow_director_enabled,
                "learning_enabled": config.workflow_learning_enabled,
            }
        if step_name == "trade_synthesis":
            return {
                "min_confidence": config.ai_trade_min_confidence,
                "require_research_confirmation": config.ai_trade_require_research_confirmation,
            }
        if step_name == "product_selection":
            return {
                "min_confidence": config.recommendation_min_confidence,
                "limit": config.max_recommendations,
                "prefer_ai_decisions": True,
            }
        if step_name == "recommendation_evaluation":
            return {
                "default_horizon_hours": config.evaluation_default_horizon_hours,
                "max_exit_lag_hours": config.evaluation_max_exit_lag_hours,
                "directional_hit_threshold_pct": config.evaluation_directional_hit_threshold_pct,
                "neutral_move_threshold_pct": config.evaluation_neutral_move_threshold_pct,
            }
        if step_name == "portfolio_risk":
            return {
                "min_correlation_samples": config.portfolio_min_correlation_samples,
                "correlation_threshold": config.portfolio_correlation_threshold,
                "max_single_concentration_pct": config.portfolio_max_single_concentration_pct,
                "max_correlated_cluster_pct": config.portfolio_max_correlated_cluster_pct,
                "max_stress_loss_pct": config.portfolio_max_stress_loss_pct,
            }
        if step_name == "ai_governance":
            return {
                "max_total_tokens_per_loop": config.ai_budget_max_total_tokens_per_loop,
                "max_cost_usd_per_loop": config.ai_budget_max_cost_usd_per_loop,
                "minimum_claim_coverage": config.ai_governance_minimum_claim_coverage,
            }
        if step_name == "execution_robot":
            return {
                "enabled": config.execution_robot_enabled,
                "max_output_tokens": config.execution_robot_max_output_tokens,
                "selection_only": True,
                "reflection_required": True,
                "mainnet_allowed": False,
            }
        if step_name == "paper_execution":
            return {
                "submit_orders": config.paper_execution_submit_orders,
                "adapters": list(config.paper_execution_adapters),
                "max_orders_per_adapter": config.paper_execution_max_orders_per_adapter,
                "max_notional_usdt": config.paper_execution_max_notional_usdt,
                "min_confidence": config.paper_execution_min_confidence,
                "credentials_configured": {
                    "gate_testnet": bool(config.gate_testnet_api_key and config.gate_testnet_api_secret),
                    "bybit_demo": bool(config.bybit_demo_api_key and config.bybit_demo_api_secret),
                },
            }
        return {}

    def _summary(
        self,
        loop_run_id: str,
        steps: list[dict[str, Any]],
        context: dict[str, Any],
        decision_readiness: dict[str, Any],
    ) -> dict[str, Any]:
        failed = [step for step in steps if step["status"] == "failed"]
        statuses: dict[str, int] = {}
        for step in steps:
            statuses[step["status"]] = statuses.get(step["status"], 0) + 1
        return {
            "loop_run_id": loop_run_id,
            "statuses": statuses,
            "failed_steps": [step["step_name"] for step in failed],
            "first_error": failed[0]["error"] if failed else None,
            "research_run_id": (context.get("research_report") or {}).get("run_id"),
            "instrument_catalog_count": (context.get("instrument_catalog") or {}).get("instrument_count", 0),
            "universe_run_id": (context.get("universe") or {}).get("universe_run_id"),
            "universe_instrument_count": len((context.get("universe") or {}).get("instruments", [])),
            "operator_report_id": (context.get("operator_report") or {}).get("report_id"),
            "product_candidate_count": (context.get("product_candidates") or {}).get("candidate_count", 0),
            "ai_debate_id": (context.get("ai_debate") or {}).get("debate_id"),
            "ai_debate_status": (context.get("ai_debate") or {}).get("status"),
            "ai_decision_count": len(context.get("ai_decisions", [])),
            "recommended_products_count": len(context.get("recommended_products", [])),
            "evaluation_run_id": (context.get("recommendation_evaluation") or {}).get("evaluation_run_id"),
            "evaluated_recommendation_count": ((context.get("recommendation_evaluation") or {}).get("summary") or {}).get("evaluated_count", 0),
            "portfolio_risk_report_id": (context.get("portfolio_risk") or {}).get("risk_report_id"),
            "portfolio_risk_status": ((context.get("portfolio_risk") or {}).get("summary") or {}).get("risk_status"),
            "execution_robot_status": (context.get("execution_robot") or {}).get("status"),
            "execution_robot_approved_count": ((context.get("execution_robot") or {}).get("summary") or {}).get("approved_count", 0),
            "ai_governance_report_id": (context.get("ai_governance") or {}).get("governance_report_id"),
            "ai_governance_status": ((context.get("ai_governance") or {}).get("summary") or {}).get("governance_status"),
            "paper_execution_run_id": (context.get("paper_execution") or {}).get("execution_run_id"),
            "paper_execution_status": (context.get("paper_execution") or {}).get("status"),
            "paper_execution_count": ((context.get("paper_execution") or {}).get("summary") or {}).get("execution_count", 0),
            "decision_readiness": decision_readiness,
            "total_duration_ms": sum(int(step.get("duration_ms") or 0) for step in steps),
        }

    def _status(self, steps: list[dict[str, Any]]) -> str:
        if any(step["status"] == "failed" for step in steps):
            return "partial" if any(step["status"] == "passed" for step in steps) else "failed"
        return "passed"


def _compact_output(output: dict[str, Any]) -> dict[str, Any]:
    compact = dict(output)
    if isinstance(compact.get("steps"), list):
        compact["steps"] = {
            "count": len(compact["steps"]),
            "failed": [
                {"step_name": step.get("step_name"), "error": step.get("error")}
                for step in compact["steps"]
                if isinstance(step, dict) and step.get("status") == "failed"
            ][:5],
        }
    if isinstance(compact.get("items"), list):
        compact["items"] = {"count": len(compact["items"]), "sample": [_compact_item(item) for item in compact["items"][:3]]}
    if isinstance(compact.get("recommended_products"), list):
        compact["recommended_products"] = {
            "count": len(compact["recommended_products"]),
            "sample": [_compact_item(item) for item in compact["recommended_products"][:5]],
        }
    if isinstance(compact.get("candidates"), list):
        compact["candidates"] = {
            "count": len(compact["candidates"]),
            "sample": [_compact_item(item) for item in compact["candidates"][:5]],
        }
    if isinstance(compact.get("instruments"), list):
        compact["instruments"] = {
            "count": len(compact["instruments"]),
            "sample": [_compact_item(item) for item in compact["instruments"][:5]],
        }
    if isinstance(compact.get("messages"), list):
        compact["messages"] = {
            "count": len(compact["messages"]),
            "roles": [str(item.get("agent_role")) for item in compact["messages"][:8] if isinstance(item, dict)],
        }
    if isinstance(compact.get("ai_decisions"), list):
        compact["ai_decisions"] = {
            "count": len(compact["ai_decisions"]),
            "sample": [_compact_item(item) for item in compact["ai_decisions"][:5]],
        }
    if isinstance(compact.get("executions"), list):
        compact["executions"] = {
            "count": len(compact["executions"]),
            "sample": [_compact_item(item) for item in compact["executions"][:5]],
        }
    for key in ("current_outcomes", "exposures", "stress_tests"):
        if isinstance(compact.get(key), list):
            compact[key] = {
                "count": len(compact[key]),
                "sample": [_compact_item(item) for item in compact[key][:5]],
            }
    if isinstance(compact.get("config"), dict):
        compact.pop("config", None)
    return compact


def _decision_readiness(context: dict[str, Any]) -> dict[str, Any]:
    operator_report = context.get("operator_report") if isinstance(context.get("operator_report"), dict) else {}
    operator_items = [item for item in operator_report.get("items", []) if isinstance(item, dict)]
    valid_market_count = sum(1 for item in operator_items if item.get("status") == "ok")
    insufficient_market_count = sum(1 for item in operator_items if item.get("status") == "insufficient-data")
    recommendations = [item for item in context.get("recommended_products", []) if isinstance(item, dict)]
    directional = [
        item
        for item in recommendations
        if str(item.get("action") or "").upper() in {"BUY", "SELL"}
        and _number(item.get("confidence"), 0.0) > 0
    ]
    unresolved_research = []
    research_statuses: dict[str, int] = {}
    for item in recommendations:
        research_context = item.get("research_context") if isinstance(item.get("research_context"), dict) else {}
        status = str(research_context.get("status") or "unknown").lower()
        research_statuses[status] = research_statuses.get(status, 0) + 1
        if status not in {"passed", "confirmed", "ready", "approved", "watch-approved", "active-watch", "market-confirmed"}:
            unresolved_research.append(item)

    reasons: list[str] = []
    if valid_market_count == 0:
        reasons.append("no_valid_market_data")
    if not recommendations:
        reasons.append("no_product_recommendations")
    if recommendations and not directional:
        reasons.append("no_directional_recommendation")
    if unresolved_research:
        reasons.append("research_evidence_unconfirmed")

    if "no_valid_market_data" in reasons:
        status = "blocked"
    elif "no_product_recommendations" in reasons:
        status = "empty"
    elif reasons:
        status = "needs-followup"
    else:
        status = "ready"
    return {
        "status": status,
        "decision_ready": status == "ready",
        "simulation_eligible": status == "ready" and bool(directional),
        "human_review_required": True,
        "reasons": reasons,
        "valid_market_count": valid_market_count,
        "insufficient_market_count": insufficient_market_count,
        "recommendation_count": len(recommendations),
        "directional_recommendation_count": len(directional),
        "unconfirmed_research_count": len(unresolved_research),
        "research_statuses": research_statuses,
    }


def _number(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _compact_item(item: Any) -> Any:
    if not isinstance(item, dict):
        return item
    keys = (
        "symbol",
        "provider",
        "market_type",
        "action",
        "status",
        "confidence",
        "score",
        "entry_reference",
        "target_price",
        "invalidation_price",
        "error",
    )
    return {key: item.get(key) for key in keys if key in item}


def _step_report(
    step_id: str,
    step_name: str,
    status: str,
    attempt: int,
    started_at: str,
    finished_at: str,
    duration_ms: int,
    output: dict[str, Any],
    error: str | None = None,
) -> dict[str, Any]:
    return {
        "step_id": step_id,
        "step_name": step_name,
        "status": status,
        "attempt": attempt,
        "started_at": started_at,
        "finished_at": finished_at,
        "duration_ms": duration_ms,
        "output": output,
        "error": error,
    }


def _artifact_ref(output: dict[str, Any]) -> str | None:
    for key in (
        "run_id",
        "report_id",
        "source_report_id",
        "debate_id",
        "universe_run_id",
        "evaluation_run_id",
        "risk_report_id",
        "governance_report_id",
        "execution_run_id",
        "loop_run_id",
    ):
        if output.get(key):
            return str(output[key])
    return None


def _failed_output(output: dict[str, Any]) -> bool:
    return str(output.get("status") or "").strip().lower() in {"failed", "error", "blocked"}


def _research_assets(store: Any, context: dict[str, Any]) -> tuple[str, ...]:
    payloads: list[Any] = [context.get("research_report") or {}]
    rows = store.list_research_councils(limit=1)
    if rows:
        try:
            payloads.append(json.loads(rows[0]["payload_json"]))
        except (TypeError, json.JSONDecodeError):
            pass
    assets: list[str] = []

    def visit(value: Any, parent_key: str = "") -> None:
        if isinstance(value, dict):
            for key, nested in value.items():
                visit(nested, str(key))
            return
        if isinstance(value, list):
            if parent_key in {"impact_assets", "asset_scope", "assets", "symbols"}:
                for item in value:
                    if isinstance(item, str) and item.strip():
                        assets.append(item.strip().upper())
            else:
                for item in value:
                    visit(item, parent_key)

    for payload in payloads:
        visit(payload)
    return tuple(dict.fromkeys(assets))


def _loop_run_id(
    config: AutonomousLoopConfig,
    trigger_type: str,
    started_at: str,
    request_context: dict[str, Any] | None = None,
) -> str:
    value = json.dumps(
        {"config": config.to_dict(), "request_context": request_context or {}},
        ensure_ascii=False,
        sort_keys=True,
        default=str,
    )
    return hashlib.sha256(f"autonomous-loop:{trigger_type}:{started_at}:{value}".encode("utf-8")).hexdigest()[:32]


def _safe_request_context(value: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    query = str(value.get("query") or "").strip()[:500]
    focus_queries = [str(item).strip()[:600] for item in value.get("focus_queries", []) if str(item).strip()][:5]
    symbols = [str(item).strip().upper()[:30] for item in value.get("symbols", []) if str(item).strip()][:12]
    product_context = value.get("product_context") if isinstance(value.get("product_context"), dict) else {}
    replay_config = value.get("replay_config") if isinstance(value.get("replay_config"), dict) else {}
    return {
        key: item
        for key, item in {
            "mode": str(value.get("mode") or "")[:80],
            "query": query,
            "focus_queries": focus_queries,
            "symbols": symbols,
            "product_context": {
                key: str(product_context.get(key) or "").strip()[:120]
                for key in ("product_id", "preferred_instrument_id", "watchlist_id", "provider", "market_type")
                if str(product_context.get(key) or "").strip()
            },
            "requested_by": str(value.get("requested_by") or "")[:80],
            "source_loop_run_id": str(value.get("source_loop_run_id") or "")[:80],
            "replay_config": {
                str(key)[:80]: item
                for key, item in replay_config.items()
                if isinstance(item, (str, int, float, bool, list, tuple))
            },
        }.items()
        if item not in ("", [], None)
    }


def _step_id(loop_run_id: str, step_name: str, attempt: int) -> str:
    return hashlib.sha256(f"{loop_run_id}:{step_name}:{attempt}".encode("utf-8")).hexdigest()


def _artifact_id(loop_run_id: str, artifact_type: str, created_at: str) -> str:
    return hashlib.sha256(f"{loop_run_id}:{artifact_type}:{created_at}".encode("utf-8")).hexdigest()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
