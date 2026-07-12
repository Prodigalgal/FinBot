from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from finbot.backtest import ReturnObservation, StrategyValidationEngine, ValidationConfig


class StrategyValidationTests(unittest.TestCase):
    def test_walk_forward_monte_carlo_sensitivity_and_ablation_are_deterministic(self) -> None:
        variants = {
            "control": _rows([0.01 if index % 3 else -0.004 for index in range(60)]),
            "challenger": _rows([0.012 if index % 4 else -0.008 for index in range(60)]),
            "without:risk": _rows([0.008 if index % 3 else -0.006 for index in range(60)]),
        }
        config = ValidationConfig(initial_train_size=30, test_size=10, monte_carlo_paths=200, random_seed=7)
        engine = StrategyValidationEngine()

        first = engine.validate(variants=variants, control_variant="control", config=config)
        second = engine.validate(variants=variants, control_variant="control", config=config)

        self.assertEqual(first, second)
        self.assertEqual(first["status"], "available")
        self.assertEqual(first["walk_forward"]["fold_count"], 3)
        self.assertEqual(first["monte_carlo"]["paths"], 200)
        self.assertEqual(first["ablation"][0]["agent_id"], "risk")
        self.assertIsNotNone(first["control_metrics"]["sortino"])
        self.assertIn("benchmark_alpha_pct", first["control_metrics"])

    def test_insufficient_samples_never_emit_fake_metrics(self) -> None:
        result = StrategyValidationEngine().validate(
            variants={"control": _rows([0.01, -0.01, 0.02])},
            control_variant="control",
        )
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["walk_forward"]["status"], "unavailable")
        self.assertEqual(result["monte_carlo"]["status"], "unavailable")

    def test_lookahead_bias_blocks_validation(self) -> None:
        invalid = _rows([0.01] * 45)
        invalid[4] = ReturnObservation(
            timestamp=invalid[4].timestamp,
            net_return=invalid[4].net_return,
            signal_timestamp=invalid[4].timestamp,
            execution_timestamp=invalid[4].timestamp - timedelta(minutes=1),
        )
        result = StrategyValidationEngine().validate(variants={"control": invalid}, control_variant="control")
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["lookahead_check"]["issues"][0]["code"], "signal_after_execution")

    def test_unaligned_variants_are_blocked(self) -> None:
        result = StrategyValidationEngine().validate(
            variants={"control": _rows([0.01] * 45), "challenger": _rows([0.01] * 44)},
            control_variant="control",
        )
        self.assertEqual(result["status"], "blocked")


def _rows(returns: list[float]) -> list[ReturnObservation]:
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return [
        ReturnObservation(
            timestamp=start + timedelta(days=index + 1),
            net_return=value,
            benchmark_return=0.001,
            signal_timestamp=start + timedelta(days=index, hours=12),
            execution_timestamp=start + timedelta(days=index, hours=13),
        )
        for index, value in enumerate(returns)
    ]


if __name__ == "__main__":
    unittest.main()
