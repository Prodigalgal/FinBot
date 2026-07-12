from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from finbot.cli.run_p1_analysis import _latest_loop_run_id
from finbot.evaluation.recommendations import RecommendationEvaluationConfig, RecommendationEvaluator
from finbot.storage.sqlite_store import SQLiteStore


class RecommendationEvaluationTests(unittest.TestCase):
    def test_matured_directional_decision_uses_point_in_time_prices(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            _insert_decision(store, created_at="2026-01-01T00:00:00+00:00", horizon="2h")
            _insert_candle(store, "2026-01-01T00:00:00+00:00", 100, 101, 99, 100)
            _insert_candle(store, "2026-01-01T01:00:00+00:00", 100, 103, 99, 102)
            _insert_candle(store, "2026-01-01T02:00:00+00:00", 102, 106, 101, 105)

            report = RecommendationEvaluator(store).evaluate(
                loop_run_id="evaluation-loop",
                config=RecommendationEvaluationConfig(default_horizon_hours=24, candle_interval="1h"),
                as_of=datetime(2026, 1, 1, 3, tzinfo=timezone.utc),
            )

        outcome = report["current_outcomes"][0]
        self.assertEqual(outcome["status"], "evaluated")
        self.assertEqual(outcome["entry_source"], "decision_entry_reference")
        self.assertEqual(outcome["directional_return_pct"], 5.0)
        self.assertTrue(outcome["hit"])
        self.assertTrue(outcome["target_hit"])
        self.assertFalse(outcome["invalidation_hit"])
        self.assertEqual(report["summary"]["directional_hit_rate"], 1.0)

    def test_unmatured_decision_remains_pending(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            _insert_decision(store, created_at="2026-01-01T00:00:00+00:00", horizon="24h")

            report = RecommendationEvaluator(store).evaluate(
                loop_run_id="evaluation-loop",
                as_of=datetime(2026, 1, 1, 1, tzinfo=timezone.utc),
            )

        self.assertEqual(report["current_outcomes"][0]["status"], "pending")
        self.assertEqual(report["summary"]["evaluated_count"], 0)

    def test_p1_cli_defaults_to_latest_completed_passed_loop(self) -> None:
        store = _LoopStore(
            [
                {"loop_run_id": "running-loop", "status": "running", "finished_at": None},
                {"loop_run_id": "failed-loop", "status": "failed", "finished_at": "2026-01-02T00:00:00+00:00"},
                {"loop_run_id": "passed-loop", "status": "passed", "finished_at": "2026-01-01T00:00:00+00:00"},
            ]
        )

        self.assertEqual(_latest_loop_run_id(store), "passed-loop")


class _LoopStore:
    def __init__(self, rows: list[dict[str, str | None]]) -> None:
        self.rows = rows

    def list_autonomous_loop_runs(self, limit: int | None = None) -> list[dict[str, str | None]]:
        return self.rows[:limit]


def _insert_decision(store: SQLiteStore, *, created_at: str, horizon: str) -> None:
    store.insert_ai_trade_decision(
        {
            "decision_id": "decision-btc",
            "loop_run_id": "source-loop",
            "debate_id": "debate-a",
            "source_report_id": "operator-a",
            "candidate_id": "candidate-btc",
            "provider": "gate",
            "market_type": "spot",
            "symbol": "BTC_USDT",
            "normalized_symbol": "BTCUSDT",
            "action": "BUY",
            "status": "candidate",
            "confidence": 0.8,
            "score": 80,
            "horizon": horizon,
            "entry_reference": 100,
            "target_price": 104,
            "invalidation_price": 98,
            "position_sizing": {"max_position_notional_pct": 5},
            "rationale": ["test"],
            "risk_warnings": [],
            "evidence_refs": ["research:test"],
            "policy": {"execution_allowed": False},
            "ai_site_id": "deepseek",
            "ai_model": "model-a",
            "prompt_version": "ptv1:test",
            "experiment_id": "experiment-a",
            "variant_id": "control",
            "created_at": created_at,
        }
    )


def _insert_candle(store: SQLiteStore, open_time: str, open_price: float, high: float, low: float, close: float) -> None:
    store.insert_market_candle(
        {
            "candle_id": f"gate:BTCUSDT:1h:{open_time}",
            "provider": "gate",
            "market_type": "spot",
            "symbol": "BTC_USDT",
            "normalized_symbol": "BTCUSDT",
            "interval": "1h",
            "open_time": open_time,
            "captured_at": "2026-01-01T03:00:00+00:00",
            "open": open_price,
            "high": high,
            "low": low,
            "close": close,
            "volume": 1,
            "turnover": close,
        }
    )


if __name__ == "__main__":
    unittest.main()
