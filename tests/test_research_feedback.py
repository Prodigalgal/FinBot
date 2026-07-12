from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from finbot.storage.sqlite_store import SQLiteStore
from finbot.workspace.feedback import ResearchFeedbackService
from finbot.workspace.reviews import DecisionReviewService


class ResearchFeedbackTests(unittest.TestCase):
    def test_refresh_builds_shadow_portfolio_without_real_account_and_generates_inbox(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_loop_and_gates(store)
            _seed_decision(store, "decision-approved", "BTC_USDT", "BTCUSDT", 60_000, "2026-07-10T00:00:00+00:00")
            _seed_decision(store, "decision-pending", "ETH_USDT", "ETHUSDT", 3_000, "2026-07-10T00:05:00+00:00")
            DecisionReviewService(store).review_decision(
                "decision-approved",
                status="approved",
                expected_version=0,
            )
            store.insert_market_quote(
                {
                    "quote_id": "quote-btc",
                    "provider": "gate",
                    "market_type": "perpetual",
                    "symbol": "BTC_USDT",
                    "normalized_symbol": "BTCUSDT",
                    "captured_at": "2026-07-10T01:00:00+00:00",
                    "last_price": 61_000,
                }
            )
            service = ResearchFeedbackService(store)

            report = service.refresh(as_of=datetime(2026, 7, 10, 1, 0, tzinfo=timezone.utc))
            notification = report["notifications"]["items"][0]
            updated = service.update_notification(notification["notification_id"], "read")

        shadow = report["shadow_portfolio"]
        self.assertEqual(shadow["status"], "ready")
        self.assertEqual(shadow["metrics"]["position_count"], 1)
        self.assertAlmostEqual(shadow["unrealized_pnl_usdt"], 16.666667, places=5)
        self.assertFalse(report["policy"]["real_account_data_used"])
        self.assertEqual(notification["category"], "review")
        self.assertEqual(updated["notification"]["status"], "read")
        self.assertEqual(report["calibration"]["status"], "insufficient_data")

    def test_missing_mark_notification_is_deduplicated_across_refreshes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_loop_and_gates(store)
            _seed_decision(store, "decision-missing-mark", "BTC_USDT", "BTCUSDT", 60_000, "2026-07-10T00:00:00+00:00")
            DecisionReviewService(store).review_decision(
                "decision-missing-mark",
                status="approved",
                expected_version=0,
            )
            service = ResearchFeedbackService(store)

            first = service.refresh(as_of=datetime(2026, 7, 10, 1, 0, tzinfo=timezone.utc))
            second = service.refresh(as_of=datetime(2026, 7, 10, 1, 5, tzinfo=timezone.utc))

        first_missing_mark = [item for item in first["notifications"]["items"] if item["category"] == "shadow_portfolio"]
        second_missing_mark = [item for item in second["notifications"]["items"] if item["category"] == "shadow_portfolio"]
        self.assertEqual(len(first_missing_mark), 1)
        self.assertEqual(len(second_missing_mark), 1)


def _seed_loop_and_gates(store: SQLiteStore) -> None:
    store.init_schema()
    store.insert_autonomous_loop_run(
        {
            "loop_run_id": "loop-feedback",
            "status": "passed",
            "trigger_type": "test",
            "config": {},
            "summary": {
                "decision_readiness": {
                    "status": "ready",
                    "decision_ready": True,
                    "simulation_eligible": True,
                    "human_review_required": True,
                    "reasons": [],
                }
            },
            "started_at": "2026-07-10T00:00:00+00:00",
            "finished_at": "2026-07-10T00:10:00+00:00",
        }
    )
    store.insert_portfolio_risk_report(
        {
            "risk_report_id": "risk-feedback",
            "loop_run_id": "loop-feedback",
            "status": "passed",
            "config": {},
            "summary": {"risk_status": "passed"},
            "risk_gate": {"status": "passed"},
            "created_at": "2026-07-10T00:10:00+00:00",
        }
    )
    store.insert_ai_governance_report(
        {
            "governance_report_id": "governance-feedback",
            "loop_run_id": "loop-feedback",
            "status": "passed",
            "config": {},
            "summary": {"governance_status": "passed"},
            "created_at": "2026-07-10T00:10:00+00:00",
        }
    )


def _seed_decision(
    store: SQLiteStore,
    decision_id: str,
    symbol: str,
    normalized_symbol: str,
    entry: float,
    created_at: str,
) -> None:
    store.insert_ai_trade_decision(
        {
            "decision_id": decision_id,
            "loop_run_id": "loop-feedback",
            "provider": "gate",
            "market_type": "perpetual",
            "symbol": symbol,
            "normalized_symbol": normalized_symbol,
            "action": "BUY",
            "status": "candidate",
            "confidence": 0.8,
            "score": 85,
            "horizon": "24h",
            "entry_reference": entry,
            "target_price": entry * 1.05,
            "invalidation_price": entry * 0.97,
            "created_at": created_at,
        }
    )


if __name__ == "__main__":
    unittest.main()
