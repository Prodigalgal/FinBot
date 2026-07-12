from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

from finbot.ai.openai_compatible import LLMCompletion, OpenAICompatibleProvider
from finbot.autonomous.execution_robot import ExecutionRobot, ExecutionRobotConfig
from finbot.config.ai_sites import AISitesConfigStore
from finbot.storage.sqlite_store import SQLiteStore


class FakeExecutionClient:
    def __init__(self, reviews: list[dict[str, Any]]) -> None:
        self.reviews = reviews
        self.requests: list[dict[str, Any]] = []

    def complete(self, **kwargs: Any) -> LLMCompletion:
        self.requests.append(kwargs)
        return LLMCompletion(
            provider=kwargs["provider"].name,
            protocol=kwargs["protocol"],
            model=kwargs["provider"].model_for(kwargs["protocol"]),
            content=json.dumps({"decision_reviews": self.reviews, "summary": "done"}),
            usage={"input_tokens": 100, "output_tokens": 20, "total_tokens": 120},
        )


class ExecutionRobotTests(unittest.TestCase):
    def test_sol_xhigh_only_selects_existing_decisions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            ai_store = AISitesConfigStore(root)
            provider = OpenAICompatibleProvider(
                name="sub2api",
                api_key="test-key",
                base_url="https://sub2api.example.test/v1",
                chat_model=None,
                responses_model="gpt-5.6-sol",
            )
            client = FakeExecutionClient(
                [
                    {"decision_id": "decision-1", "execute": True, "priority": 1, "reason": "gates passed"},
                    {"decision_id": "decision-2", "execute": False, "priority": 2, "reason": "risk conflict"},
                    {"decision_id": "invented", "execute": True, "priority": 3, "reason": "invalid"},
                ]
            )
            robot = ExecutionRobot(store, {"sub2api": provider}, ai_store, client=client)
            decisions = [_decision("decision-1", "BUY"), _decision("decision-2", "SELL")]

            report = robot.run(
                decisions=decisions,
                portfolio_risk={"risk_gate": {"status": "passed"}},
                config=ExecutionRobotConfig(loop_run_id="loop-1", debate_id="debate-1"),
            )
            invocations = [dict(row) for row in store.list_ai_invocations(loop_run_id="loop-1")]

        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["approved_decision_ids"], ["decision-1"])
        self.assertEqual(report["approved_decisions"][0]["action"], "BUY")
        self.assertFalse(report["policy"]["ai_controls_order_size"])
        self.assertTrue(report["policy"]["reflection_required"])
        self.assertEqual(report["reflection"]["status"], "passed")
        self.assertEqual(len(report["initial_decision_reviews"]), 2)
        self.assertEqual(len(client.requests), 2)
        self.assertEqual(client.requests[0]["protocol"], "responses")
        self.assertEqual(client.requests[0]["reasoning_effort"], "xhigh")
        self.assertEqual(client.requests[0]["provider"].responses_model, "gpt-5.6-sol")
        self.assertEqual({item["role_id"] for item in invocations}, {"execution_robot_initial", "execution_robot_reflection"})
        self.assertTrue(all(item["task_id"] == "ai_execution_robot" for item in invocations))
        self.assertTrue(all(item["model"] == "gpt-5.6-sol" for item in invocations))

    def test_missing_reviews_fail_closed_and_risk_block_skips_ai(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            provider = OpenAICompatibleProvider(
                name="sub2api",
                api_key="test-key",
                base_url="https://sub2api.example.test/v1",
                chat_model=None,
                responses_model="gpt-5.6-sol",
            )
            client = FakeExecutionClient([])
            robot = ExecutionRobot(store, {"sub2api": provider}, AISitesConfigStore(root), client=client)

            fail_closed = robot.run(
                decisions=[_decision("decision-1", "BUY")],
                portfolio_risk={"risk_gate": {"status": "warning"}},
                config=ExecutionRobotConfig(loop_run_id="loop-2"),
            )
            blocked = robot.run(
                decisions=[_decision("decision-2", "SELL")],
                portfolio_risk={"risk_gate": {"status": "blocked"}},
                config=ExecutionRobotConfig(loop_run_id="loop-3"),
            )

        self.assertEqual(fail_closed["status"], "passed")
        self.assertEqual(fail_closed["approved_decisions"], [])
        self.assertEqual(fail_closed["decision_reviews"][0]["risk_flags"], ["missing_review"])
        self.assertEqual(blocked["status"], "blocked")
        self.assertEqual(len(client.requests), 2)


def _decision(decision_id: str, action: str) -> dict[str, Any]:
    return {
        "decision_id": decision_id,
        "candidate_id": f"candidate-{decision_id}",
        "symbol": "BTCUSDT",
        "provider": "gate",
        "market_type": "perpetual",
        "action": action,
        "status": "candidate",
        "confidence": 0.8,
        "entry_reference": 100.0,
        "target_price": 105.0 if action == "BUY" else 95.0,
        "invalidation_price": 98.0 if action == "BUY" else 102.0,
        "rationale": ["test"],
        "risk_warnings": [],
        "evidence_refs": ["evidence-1"],
    }


if __name__ == "__main__":
    unittest.main()
