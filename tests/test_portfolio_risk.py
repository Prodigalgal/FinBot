from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from finbot.risk.portfolio import PortfolioRiskAnalyzer, PortfolioRiskConfig
from finbot.storage.sqlite_store import SQLiteStore


class PortfolioRiskTests(unittest.TestCase):
    def test_provider_concentration_uses_configured_execution_venues(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            report = PortfolioRiskAnalyzer(store).analyze(
                loop_run_id="risk-execution-venues",
                recommendations=[
                    _recommendation("decision-btc", "BTCUSDT", 5),
                    _recommendation("decision-eth", "ETHUSDT", 5),
                ],
                config=PortfolioRiskConfig(
                    execution_providers=("gate", "bybit"),
                    max_single_product_concentration_pct=60,
                ),
            )

        self.assertEqual(report["risk_gate"]["status"], "warning")
        self.assertEqual(report["concentration"]["provider_basis"], "configured_execution_providers")
        self.assertEqual(report["concentration"]["largest_provider_concentration_pct"], 50.0)
        self.assertEqual(
            {item["group"] for item in report["concentration"]["groups"]["provider"]},
            {"gate", "bybit"},
        )

    def test_correlated_concentrated_book_is_blocked_by_advisory_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            start = datetime(2026, 1, 1, tzinfo=timezone.utc)
            for index in range(12):
                timestamp = (start + timedelta(hours=index)).isoformat()
                _insert_candle(store, "BTCUSDT", timestamp, 100 + index)
                _insert_candle(store, "ETHUSDT", timestamp, 50 + index * 0.5)

            report = PortfolioRiskAnalyzer(store).analyze(
                loop_run_id="risk-loop",
                recommendations=[
                    _recommendation("decision-btc", "BTCUSDT", 5),
                    _recommendation("decision-eth", "ETHUSDT", 5),
                ],
                config=PortfolioRiskConfig(
                    lookback_points=12,
                    min_correlation_samples=5,
                    max_single_product_concentration_pct=35,
                    max_correlated_cluster_concentration_pct=60,
                    max_hypothetical_stress_loss_pct=0.5,
                ),
            )

        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["risk_gate"]["status"], "blocked")
        self.assertEqual(report["correlations"]["pairs"][0]["status"], "available")
        self.assertGreater(report["correlations"]["pairs"][0]["raw_correlation"], 0.99)
        self.assertEqual(report["concentration"]["largest_product_concentration_pct"], 50.0)
        self.assertEqual(report["concentration"]["largest_correlated_cluster_concentration_pct"], 100.0)

    def test_missing_correlation_data_is_reported_as_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            report = PortfolioRiskAnalyzer(store).analyze(
                loop_run_id="risk-loop",
                recommendations=[
                    _recommendation("decision-btc", "BTCUSDT", 5),
                    _recommendation("decision-eth", "ETHUSDT", 5),
                ],
            )

        self.assertEqual(report["correlations"]["status"], "insufficient_data")
        codes = {item["code"] for item in report["risk_gate"]["reasons"]}
        self.assertIn("correlation_data_insufficient", codes)


def _recommendation(decision_id: str, symbol: str, weight: float) -> dict:
    return {
        "decision_id": decision_id,
        "provider": "gate",
        "market_type": "spot",
        "symbol": symbol,
        "normalized_symbol": symbol,
        "action": "BUY",
        "confidence": 0.8,
        "position_sizing": {"max_position_notional_pct": weight},
    }


def _insert_candle(store: SQLiteStore, symbol: str, open_time: str, close: float) -> None:
    store.insert_market_candle(
        {
            "candle_id": f"gate:{symbol}:1h:{open_time}",
            "provider": "gate",
            "market_type": "spot",
            "symbol": symbol,
            "normalized_symbol": symbol,
            "interval": "1h",
            "open_time": open_time,
            "captured_at": open_time,
            "open": close - 0.1,
            "high": close + 0.2,
            "low": close - 0.2,
            "close": close,
            "volume": 1,
            "turnover": close,
        }
    )


if __name__ == "__main__":
    unittest.main()
