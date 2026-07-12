from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.council.learning import CouncilLearningService
from finbot.storage.sqlite_store import SQLiteStore


class CouncilLearningTests(unittest.TestCase):
    def test_scores_reflection_and_selective_memory_are_auditable_and_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            _seed_learning_fixture(store)
            service = CouncilLearningService(store)

            first = service.refresh_debate("debate-learning")
            second = service.refresh_debate("debate-learning")
            memory = service.retrieve(
                template_id="product_advisory",
                role_id="bull_researcher",
                product_id="candidate-btc",
                symbol="BTCUSDT",
                market_type="perpetual",
                topics=["BTC ETF 资金流"],
                limit=2,
                max_chars=4_000,
            )
            snapshot = service.snapshot(template_id="product_advisory")

        scores = {item["role_id"]: item for item in first["scores"]}
        self.assertGreater(scores["bull_researcher"]["score"], scores["bear_researcher"]["score"])
        self.assertEqual(scores["bull_researcher"]["components"]["mature_outcome"], 1.0)
        self.assertIn("缺少链上数据", first["reflection"]["missing_evidence"])
        self.assertFalse(first["reflection"]["automatic_prompt_changes_allowed"])
        self.assertGreater(first["memories_created"], 0)
        self.assertEqual(second["memories_created"], 0)
        self.assertLessEqual(memory["count"], 2)
        self.assertTrue(
            all(item["role_id"] in {None, "bull_researcher"} for item in memory["items"])
        )
        self.assertTrue(all(item["source_refs"] for item in memory["items"]))
        self.assertFalse(snapshot["policy"]["automatic_workflow_publish_allowed"])


def _seed_learning_fixture(store: SQLiteStore) -> None:
    now = "2026-07-12T00:00:00+00:00"
    store.insert_ai_debate_council(
        {
            "debate_id": "debate-learning",
            "loop_run_id": "loop-learning",
            "template_id": "product_advisory",
            "status": "passed",
            "rounds": 1,
            "summary": {},
            "round_summaries": [],
            "payload": {
                "user_query": "BTC ETF 资金流",
                "candidates": [
                    {
                        "candidate_id": "candidate-btc",
                        "symbol": "BTC_USDT",
                        "normalized_symbol": "BTCUSDT",
                        "market_type": "perpetual",
                    }
                ],
            },
            "created_at": now,
        }
    )
    for message in (
        _message("message-bull", "bull_researcher", "independent_analysis", ["research:btc"], now),
        _message("message-bear", "bear_researcher", "independent_analysis", ["research:bear"], now),
        {
            **_message("message-chair", "chair_arbiter", "chair_synthesis", ["research:btc"], now),
            "message_type": "decision",
            "content": {
                "debate_summary": ["多方证据被主席采纳。"],
                "missing_evidence": ["缺少链上数据"],
                "decisions": [{"evidence_refs": ["research:btc"]}],
            },
        },
    ):
        store.insert_ai_debate_message(message)
    store.replace_claim_evidence_audits(
        "debate-learning",
        [
            _audit("audit-bull", "message-bull", "bull_researcher", True, "资金流证据覆盖", now),
            _audit("audit-bear", "message-bear", "bear_researcher", False, "链上风险未覆盖", now),
        ],
    )
    store.insert_ai_trade_decision(
        {
            "decision_id": "decision-learning",
            "loop_run_id": "loop-learning",
            "debate_id": "debate-learning",
            "candidate_id": "candidate-btc",
            "provider": "gate",
            "market_type": "perpetual",
            "symbol": "BTC_USDT",
            "normalized_symbol": "BTCUSDT",
            "action": "BUY",
            "status": "candidate",
            "confidence": 0.8,
            "score": 80,
            "horizon": "24h",
            "entry_reference": 100,
            "target_price": 104,
            "invalidation_price": 98,
            "position_sizing": {},
            "rationale": ["test"],
            "risk_warnings": [],
            "evidence_refs": ["research:btc"],
            "policy": {"execution_allowed": False},
            "created_at": now,
        }
    )
    store.save_decision_review(
        {
            "review_id": "review-learning",
            "decision_id": "decision-learning",
            "loop_run_id": "loop-learning",
            "status": "approved",
            "reviewer_id": "operator",
            "note": "人工确认研究结论",
            "metadata": {},
            "created_at": now,
            "updated_at": now,
            "reviewed_at": now,
        },
        expected_version=0,
    )
    store.insert_recommendation_outcome(
        {
            "outcome_id": "outcome-learning",
            "evaluation_run_id": "evaluation-learning",
            "decision_id": "decision-learning",
            "loop_run_id": "loop-learning",
            "horizon_hours": 24,
            "status": "evaluated",
            "action": "BUY",
            "confidence": 0.8,
            "provider": "gate",
            "market_type": "perpetual",
            "symbol": "BTC_USDT",
            "normalized_symbol": "BTCUSDT",
            "decision_at": now,
            "horizon_at": "2026-07-13T00:00:00+00:00",
            "evaluated_at": "2026-07-13T01:00:00+00:00",
            "entry_price": 100,
            "exit_price": 104,
            "directional_return_pct": 4.0,
            "hit": True,
            "target_hit": True,
            "invalidation_hit": False,
        }
    )


def _message(
    message_id: str,
    role_id: str,
    phase_id: str,
    evidence_refs: list[str],
    created_at: str,
) -> dict:
    return {
        "message_id": message_id,
        "debate_id": "debate-learning",
        "loop_run_id": "loop-learning",
        "round_index": 1 if phase_id != "chair_synthesis" else 2,
        "turn_index": 1,
        "phase_id": phase_id,
        "message_type": "analysis",
        "agent_role": role_id,
        "stance": "neutral",
        "status": "completed",
        "content": {
            "overall_view": "test",
            "candidate_assessments": [{"evidence_refs": evidence_refs}],
        },
        "reply_to_message_ids": [],
        "usage": {},
        "created_at": created_at,
    }


def _audit(
    audit_id: str,
    message_id: str,
    role_id: str,
    covered: bool,
    claim_text: str,
    created_at: str,
) -> dict:
    return {
        "audit_id": audit_id,
        "loop_run_id": "loop-learning",
        "debate_id": "debate-learning",
        "message_id": message_id,
        "role_id": role_id,
        "phase_id": "independent_analysis",
        "claim_id": f"claim-{role_id}",
        "claim_source": "explicit",
        "claim_text": claim_text,
        "covered": covered,
        "derived": False,
        "evidence_refs": ["research:btc"] if covered else [],
        "created_at": created_at,
    }


if __name__ == "__main__":
    unittest.main()
