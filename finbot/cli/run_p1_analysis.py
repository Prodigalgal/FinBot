from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from finbot.ai.governance import AIGovernanceConfig, AIGovernanceReporter
from finbot.autonomous.config import AutonomousLoopConfig
from finbot.cli.common import build_store, write_report
from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.paths import runtime_root
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.evaluation.recommendations import RecommendationEvaluationConfig, RecommendationEvaluator
from finbot.risk.portfolio import PortfolioRiskAnalyzer, PortfolioRiskConfig


def main() -> None:
    parser = argparse.ArgumentParser(description="Recompute P1 evaluation, portfolio risk, and AI governance reports.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--loop-run-id", default=None)
    args = parser.parse_args()

    settings, store = build_store(args.data_dir)
    runtime = AutonomousLoopConfig.from_runtime_config(
        RuntimeConfigStore(runtime_root(Path.cwd())),
        data_dir=args.data_dir,
    )
    loop_run_id = args.loop_run_id or _latest_loop_run_id(store)
    if not loop_run_id:
        raise SystemExit("No autonomous loop run is available for P1 analysis.")
    intervals = runtime.intervals or ("1h",)
    interval = "1h" if "1h" in intervals else intervals[0]

    evaluation = RecommendationEvaluator(store).evaluate(
        loop_run_id=loop_run_id,
        config=RecommendationEvaluationConfig(
            default_horizon_hours=runtime.evaluation_default_horizon_hours,
            candle_interval=interval,
            max_exit_lag_hours=runtime.evaluation_max_exit_lag_hours,
            directional_hit_threshold_pct=runtime.evaluation_directional_hit_threshold_pct,
            neutral_move_threshold_pct=runtime.evaluation_neutral_move_threshold_pct,
        ),
    )
    decisions = [_decision_payload(row) for row in store.list_ai_trade_decisions(loop_run_id=loop_run_id)]
    portfolio_risk = PortfolioRiskAnalyzer(store).analyze(
        loop_run_id=loop_run_id,
        recommendations=decisions,
        config=PortfolioRiskConfig(
            candle_interval=interval,
            lookback_points=max(2, runtime.candle_limit),
            min_correlation_samples=runtime.portfolio_min_correlation_samples,
            correlation_threshold=runtime.portfolio_correlation_threshold,
            max_single_product_concentration_pct=runtime.portfolio_max_single_concentration_pct,
            max_correlated_cluster_concentration_pct=runtime.portfolio_max_correlated_cluster_pct,
            max_hypothetical_stress_loss_pct=runtime.portfolio_max_stress_loss_pct,
        ),
    )
    debate_rows = store.list_ai_debate_councils(limit=1, loop_run_id=loop_run_id)
    debate_id = debate_rows[0]["debate_id"] if debate_rows else None
    governance = AIGovernanceReporter(store, AISitesConfigStore(runtime_root(Path.cwd()))).build(
        loop_run_id=loop_run_id,
        debate_id=debate_id,
        config=AIGovernanceConfig(
            max_total_tokens_per_loop=runtime.ai_budget_max_total_tokens_per_loop,
            max_cost_usd_per_loop=runtime.ai_budget_max_cost_usd_per_loop,
            minimum_claim_evidence_coverage=runtime.ai_governance_minimum_claim_coverage,
        ),
    )

    outputs = {
        "recommendation_evaluation": str(write_report(settings, "recommendation-evaluation-latest.json", evaluation)),
        "portfolio_risk": str(write_report(settings, "portfolio-risk-latest.json", portfolio_risk)),
        "ai_governance": str(write_report(settings, "ai-governance-latest.json", governance)),
    }
    print(
        json.dumps(
            {
                "status": "passed",
                "loop_run_id": loop_run_id,
                "evaluation": evaluation["summary"],
                "portfolio_risk": portfolio_risk["summary"],
                "ai_governance": governance["summary"],
                "outputs": outputs,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


def _latest_loop_run_id(store: Any) -> str | None:
    rows = store.list_autonomous_loop_runs(limit=100)
    for row in rows:
        if row["status"] == "passed" and row["finished_at"]:
            return str(row["loop_run_id"])
    return None


def _decision_payload(row: Any) -> dict[str, Any]:
    try:
        payload = json.loads(row["payload_json"] or "{}")
    except (TypeError, json.JSONDecodeError):
        payload = {}
    if not isinstance(payload, dict):
        payload = {}
    return {
        **payload,
        "decision_id": row["decision_id"],
        "provider": row["provider"],
        "market_type": row["market_type"],
        "symbol": row["symbol"],
        "normalized_symbol": row["normalized_symbol"],
        "action": row["action"],
        "confidence": row["confidence"],
        "position_sizing": json.loads(row["position_sizing_json"] or "{}"),
    }


if __name__ == "__main__":
    main()
