from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from finbot.experiments import ExperimentDefinition, ExperimentRegistry, ExperimentRun
from finbot.risk import PnlAttributionRecord, attribute_pnl
from finbot.storage.sqlite_store import SQLiteStore


class ExperimentRegistryTests(unittest.TestCase):
    def test_control_challenger_comparison_is_reproducible_and_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            registry = ExperimentRegistry(SQLiteStore(Path(temp_dir) / "finbot.sqlite3"))
            registry.save_definition(
                ExperimentDefinition(
                    experiment_id="exp-1",
                    name="Council depth",
                    control_variant="control",
                    challenger_variants=("deep",),
                    data_version="candles-v1",
                    workflow_version="workflow-v2",
                    model_version="gpt-5.6-luna",
                )
            )
            control = _run("run-control", "control", {"net_return_pct": 4, "max_drawdown_pct": 3, "sharpe": 1.1})
            deep = _run("run-deep", "deep", {"net_return_pct": 5.5, "max_drawdown_pct": 4, "sharpe": 1.3})
            self.assertEqual(registry.record_run(control), registry.record_run(control))
            registry.record_run(deep)
            comparison = registry.comparison("exp-1", "input-1")

        self.assertEqual(comparison["status"], "available")
        challenger = next(row for row in comparison["variants"] if row["role"] == "challenger")
        self.assertEqual(challenger["delta_vs_control"]["net_return_pct"], 1.5)
        self.assertTrue(comparison["reproducibility"]["same_data_version"])

    def test_data_version_and_unknown_variant_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            registry = ExperimentRegistry(SQLiteStore(Path(temp_dir) / "finbot.sqlite3"))
            registry.save_definition(ExperimentDefinition("exp-2", "Test", "control", (), "v1"))
            with self.assertRaisesRegex(ValueError, "data_version"):
                registry.record_run(_run("bad-version", "control", {}, data_version="v2", experiment_id="exp-2"))
            with self.assertRaisesRegex(ValueError, "variant_id"):
                registry.record_run(_run("bad-variant", "unknown", {}, experiment_id="exp-2"))

    def test_pnl_attribution_reconciles_to_total(self) -> None:
        result = attribute_pnl(
            [
                PnlAttributionRecord("gate", "BTC", "trend", 10, fee_usdt=1, funding_usdt=-0.5),
                PnlAttributionRecord("bybit", "ETH", "mean", -3, fee_usdt=0.5, funding_usdt=0.25),
            ]
        )
        self.assertEqual(result["totals"]["net_pnl_usdt"], 5.25)
        self.assertEqual(sum(row["net_pnl_usdt"] for row in result["by_venue"]), 5.25)


def _run(
    run_id: str,
    variant_id: str,
    metrics: dict[str, float],
    *,
    data_version: str = "candles-v1",
    experiment_id: str = "exp-1",
) -> ExperimentRun:
    return ExperimentRun(
        run_id=run_id,
        experiment_id=experiment_id,
        variant_id=variant_id,
        input_hash="input-1",
        data_version=data_version,
        random_seed=7,
        status="passed",
        metrics=metrics,
        config={"periods_per_year": 365},
        created_at=datetime.now(timezone.utc).isoformat(),
    )


if __name__ == "__main__":
    unittest.main()
