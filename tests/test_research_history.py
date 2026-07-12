from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.storage.sqlite_store import SQLiteStore
from finbot.workspace.history import ResearchHistoryService


class ResearchHistoryTests(unittest.TestCase):
    def test_history_compare_and_replay_create_new_audited_request(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            _seed_run(store, "loop-a", "BUY", 0.8, "2026-07-11T00:00:00+00:00")
            _seed_run(store, "loop-b", "WATCH", 0.0, "2026-07-11T01:00:00+00:00")
            service = ResearchHistoryService(store)

            history = service.list_runs()
            comparison = service.compare_runs("loop-a", "loop-b")
            replay = service.replay_run("loop-a")

        self.assertEqual(history["count"], 2)
        self.assertEqual(history["items"][0]["loop_run_id"], "loop-b")
        self.assertTrue(any(item["changed"] for item in comparison["decision_changes"]))
        self.assertEqual(replay["request"]["trigger_type"], "replay")
        self.assertEqual(replay["replay"]["source_loop_run_id"], "loop-a")
        self.assertFalse(replay["replay"]["config"].get("paper_execution_submit_orders", False))

    def test_resume_selects_first_failed_research_step(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            _seed_run(store, "loop-failed", "WATCH", 0.0, "2026-07-11T00:00:00+00:00")
            store.insert_research_pipeline_run(
                {
                    "run_id": "pipeline-failed",
                    "profile": "test",
                    "status": "failed",
                    "triggered_by": "autonomous:loop-failed",
                    "config": {},
                    "summary": {},
                    "started_at": "2026-07-11T00:00:00+00:00",
                    "finished_at": "2026-07-11T00:01:00+00:00",
                    "error": "compression failed",
                }
            )
            store.insert_research_pipeline_step(
                {
                    "step_id": "step-preflight",
                    "run_id": "pipeline-failed",
                    "step_name": "preflight",
                    "status": "passed",
                    "attempt": 1,
                    "started_at": "2026-07-11T00:00:00+00:00",
                    "finished_at": "2026-07-11T00:00:01+00:00",
                    "duration_ms": 1000,
                    "input": {},
                    "output": {},
                }
            )
            store.insert_research_pipeline_step(
                {
                    "step_id": "step-compression",
                    "run_id": "pipeline-failed",
                    "step_name": "ai_compression",
                    "status": "failed",
                    "attempt": 1,
                    "started_at": "2026-07-11T00:00:01+00:00",
                    "finished_at": "2026-07-11T00:01:00+00:00",
                    "duration_ms": 59000,
                    "input": {},
                    "output": {},
                    "error": "compression failed",
                }
            )

            resume = ResearchHistoryService(store).resume_request("loop-failed")

        self.assertEqual(resume["resume_run_id"], "pipeline-failed")
        self.assertEqual(resume["from_step"], "ai_compression")


def _seed_run(store: SQLiteStore, loop_run_id: str, action: str, confidence: float, started_at: str) -> None:
    directional = action in {"BUY", "SELL"}
    store.insert_autonomous_loop_run(
        {
            "loop_run_id": loop_run_id,
            "status": "passed",
            "trigger_type": "test",
            "config": {"symbols": ["BTCUSDT"], "paper_execution_submit_orders": True},
            "summary": {
                "total_duration_ms": 1000,
                "decision_readiness": {
                    "status": "ready" if directional else "needs-followup",
                    "decision_ready": directional,
                    "simulation_eligible": directional,
                    "reasons": [] if directional else ["no_directional_recommendations"],
                },
            },
            "started_at": started_at,
            "finished_at": started_at,
        }
    )
    store.insert_ai_trade_decision(
        {
            "decision_id": f"decision-{loop_run_id}",
            "loop_run_id": loop_run_id,
            "provider": "gate",
            "market_type": "perpetual",
            "symbol": "BTC_USDT",
            "normalized_symbol": "BTCUSDT",
            "action": action,
            "status": "candidate" if directional else "watch",
            "confidence": confidence,
            "score": 80,
            "entry_reference": 60_000,
            "target_price": 63_000 if action != "SELL" else 57_000,
            "invalidation_price": 58_000 if action != "SELL" else 62_000,
            "created_at": started_at,
        }
    )


if __name__ == "__main__":
    unittest.main()
