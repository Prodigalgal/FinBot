from __future__ import annotations

import json
import unittest

from finbot.web.operations_stream import operations_update_payload


class OperationsStreamPayloadTests(unittest.TestCase):
    def test_compact_update_strips_worker_history_and_unchanged_result_details(self) -> None:
        snapshot = _snapshot("loop-result-1")

        payload = operations_update_payload(snapshot, _snapshot("loop-result-1"))

        self.assertTrue(payload["partial"])
        self.assertEqual(len(payload["autonomous"]["worker"]["workers"]), 1)
        self.assertEqual(len(payload["autonomous"]["worker"]["recent_requests"]), 6)
        self.assertEqual(payload["autonomous"]["worker"]["recent_requests"][0]["result"], {})
        self.assertNotIn("latest_ai_debates", payload["autonomous"])
        self.assertLess(len(json.dumps(payload, ensure_ascii=False).encode("utf-8")), 20_000)

    def test_compact_update_includes_complete_result_when_result_run_changes(self) -> None:
        snapshot = _snapshot("loop-result-2")

        payload = operations_update_payload(snapshot, _snapshot("loop-result-1"))

        self.assertEqual(payload["autonomous"]["latest_result_loop_run_id"], "loop-result-2")
        self.assertEqual(payload["autonomous"]["latest_ai_debates"], [{"debate_id": "debate-1"}])
        self.assertEqual(payload["autonomous"]["latest_recommendations"], [{"symbol": "BTCUSDT"}])


def _snapshot(result_run_id: str) -> dict[str, object]:
    requests = [
        {
            "request_id": f"request-{index}",
            "trigger_type": "scheduler",
            "status": "running" if index == 0 else "succeeded",
            "requested_at": "2026-07-13T01:00:00Z",
            "attempt": 1,
            "payload": {"large": "x" * 5_000},
            "result": {"large": "y" * 5_000},
        }
        for index in range(10)
    ]
    run = {
        "loop_run_id": "loop-current",
        "status": "running",
        "trigger_type": "scheduler",
        "summary": {},
        "decision_readiness": None,
        "started_at": "2026-07-13T01:00:00Z",
        "finished_at": None,
        "error": None,
        "steps": [
            {
                "step_id": "step-1",
                "step_name": "research_pipeline",
                "status": "passed",
                "attempt": 1,
                "duration_ms": 100,
                "input": {"large": "x" * 5_000},
                "output": {"status": "passed", "items": [{"id": index} for index in range(20)]},
            }
        ],
    }
    return {
        "status": {
            "status": "ok",
            "service": "finbot-web",
            "generated_at": "2026-07-13T01:00:00Z",
            "counts": {"raw_evidence": 10},
            "source_statuses": {"ready": 1},
            "autonomous_scheduler": {"status": "running"},
            "latest_autonomous_loop": run,
            "latest_pipeline_run": None,
            "latest_advisory_report": None,
            "policy": {},
        },
        "autonomous": {
            "status": "ok",
            "generated_at": "2026-07-13T01:00:00Z",
            "scheduler": {"status": "running"},
            "worker": {
                "queue": {"running": 1},
                "workers": [
                    {"worker_id": "active", "active": True},
                    {"worker_id": "stale", "active": False},
                ],
                "leases": [{"lease_id": "large-history"}],
                "scheduler": {"next_run_at": "2026-07-13T02:00:00Z"},
                "recent_requests": requests,
            },
            "config": {"enabled": True},
            "recent_runs": [run],
            "latest_result_loop_run_id": result_run_id,
            "latest_decision_readiness": None,
            "paper_execution": {"status": "ready"},
            "policy": {},
            "latest_recommendations": [{"symbol": "BTCUSDT"}],
            "latest_ai_debates": [{"debate_id": "debate-1"}],
            "latest_ai_decisions": [],
            "latest_universe": None,
            "latest_evaluation": None,
            "latest_portfolio_risk": None,
            "latest_ai_governance": None,
        },
        "jobs": [],
    }


if __name__ == "__main__":
    unittest.main()
