from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.storage.sqlite_store import SQLiteStore, StaleRecordError
from finbot.workspace.reviews import DecisionReviewService


class DecisionReviewTests(unittest.TestCase):
    def test_risk_warning_remains_approval_eligible(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_decision(store, risk_status="warning")
            service = DecisionReviewService(store)

            approved = service.review_decision("decision-btc", status="approved", expected_version=0)

        self.assertEqual(approved["decision_readiness"]["status"], "ready")
        self.assertTrue(approved["approval_eligible"])
        self.assertEqual(approved["review"]["status"], "approved")

    def test_implicit_pending_review_can_be_approved_with_all_gates(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_decision(store)
            service = DecisionReviewService(store)

            inbox = service.list_inbox(status="pending")
            approved = service.review_decision(
                "decision-btc",
                status="approved",
                note="风险与证据已人工确认",
                expected_version=0,
            )
            executable = service.approved_decision("decision-btc")

        self.assertEqual(inbox["count"], 1)
        self.assertTrue(inbox["items"][0]["approval_eligible"])
        self.assertEqual(approved["review"]["status"], "approved")
        self.assertEqual(approved["review"]["version"], 1)
        self.assertEqual(executable["human_review_status"], "approved")

    def test_approval_rejects_blocked_readiness_and_stale_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_decision(store, simulation_eligible=False)
            service = DecisionReviewService(store)

            with self.assertRaisesRegex(ValueError, "决策就绪度"):
                service.review_decision("decision-btc", status="approved", expected_version=0)
            first = service.review_decision("decision-btc", status="rejected", expected_version=0)
            with self.assertRaises(StaleRecordError):
                service.review_decision("decision-btc", status="changes_requested", expected_version=0)

        self.assertEqual(first["review"]["version"], 1)


def _seed_decision(
    store: SQLiteStore,
    simulation_eligible: bool = True,
    risk_status: str = "passed",
) -> None:
    store.init_schema()
    readiness = {
        "status": "ready" if simulation_eligible else "needs-followup",
        "decision_ready": simulation_eligible,
        "simulation_eligible": simulation_eligible,
        "human_review_required": True,
        "reasons": [] if simulation_eligible else ["unconfirmed_research"],
    }
    store.insert_autonomous_loop_run(
        {
            "loop_run_id": "loop-btc",
            "status": "passed",
            "trigger_type": "test",
            "config": {},
            "summary": {"decision_readiness": readiness},
            "started_at": "2026-07-11T00:00:00+00:00",
            "finished_at": "2026-07-11T00:01:00+00:00",
        }
    )
    store.insert_ai_trade_decision(
        {
            "decision_id": "decision-btc",
            "loop_run_id": "loop-btc",
            "provider": "gate",
            "market_type": "perpetual",
            "symbol": "BTC_USDT",
            "normalized_symbol": "BTCUSDT",
            "action": "BUY",
            "status": "candidate",
            "confidence": 0.82,
            "score": 88,
            "entry_reference": 60_000,
            "target_price": 63_000,
            "invalidation_price": 58_000,
            "position_sizing": {},
            "rationale": ["test"],
            "risk_warnings": [],
            "evidence_refs": ["evidence-1"],
            "policy": {},
            "created_at": "2026-07-11T00:01:00+00:00",
        }
    )
    store.insert_portfolio_risk_report(
        {
            "risk_report_id": "risk-btc",
            "loop_run_id": "loop-btc",
            "status": "passed",
            "config": {},
            "summary": {"risk_status": risk_status},
            "risk_gate": {"status": risk_status},
            "created_at": "2026-07-11T00:01:00+00:00",
        }
    )
    store.insert_ai_governance_report(
        {
            "governance_report_id": "governance-btc",
            "loop_run_id": "loop-btc",
            "status": "passed",
            "config": {},
            "summary": {"governance_status": "passed"},
            "created_at": "2026-07-11T00:01:00+00:00",
        }
    )


if __name__ == "__main__":
    unittest.main()
