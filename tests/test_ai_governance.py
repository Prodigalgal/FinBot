from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.ai.governance import (
    AIBudgetGuard,
    AIBudgetPolicy,
    AIGovernanceConfig,
    AIGovernanceReporter,
    AIInvocationRecorder,
    prompt_input_hash,
)
from finbot.config.ai_sites import AISitesConfigStore
from finbot.storage.sqlite_store import SQLiteStore


class AIGovernanceTests(unittest.TestCase):
    def test_invocation_cost_and_claim_coverage_are_audited(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            ai_store = AISitesConfigStore(root)
            payload = ai_store.default_payload()
            payload["sites"][0]["input_cost_per_million_tokens"] = 1.0
            payload["sites"][0]["output_cost_per_million_tokens"] = 2.0
            payload["sites"][0]["pricing_model"] = "model-a"
            ai_store.update(payload)
            recorder = AIInvocationRecorder(store, ai_store)
            prompt_version = recorder.register_prompt(
                task_id="ai_debate",
                role_id="evidence_auditor",
                system_prompt="system",
                user_prompt_template="{payload_json}",
            )
            recorder.record(
                loop_run_id="loop-a",
                debate_id="debate-a",
                message_id="message-a",
                task_id="ai_debate",
                role_id="evidence_auditor",
                site_id="deepseek",
                protocol="chat",
                model="model-a",
                prompt_version=prompt_version,
                input_hash=prompt_input_hash("system", "user"),
                experiment_id="experiment-a",
                variant_id="control",
                status="completed",
                usage={"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
                duration_ms=100,
                error=None,
                attempt_index=1,
            )
            _insert_message(store)

            report = AIGovernanceReporter(store).build(
                loop_run_id="loop-a",
                debate_id="debate-a",
                config=AIGovernanceConfig(minimum_claim_evidence_coverage=0.8),
            )

        self.assertEqual(report["summary"]["total_tokens"], 30)
        self.assertEqual(report["summary"]["cost_status"], "known")
        self.assertAlmostEqual(report["summary"]["estimated_cost_usd"], 0.00005)
        self.assertEqual(report["summary"]["claim_evidence_coverage"], 0.5)
        self.assertEqual(report["summary"]["governance_status"], "warning")

    def test_report_reprices_legacy_invocation_without_rewriting_source_row(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            ai_store = AISitesConfigStore(root)
            payload = ai_store.default_payload()
            payload["sites"][0]["pricing_model"] = "model-a"
            payload["sites"][0]["input_cost_per_million_tokens"] = None
            payload["sites"][0]["output_cost_per_million_tokens"] = None
            ai_store.update(payload)
            recorder = AIInvocationRecorder(store, ai_store)
            recorder.record(
                loop_run_id="loop-reprice",
                debate_id="debate-reprice",
                message_id="message-reprice",
                task_id="ai_debate",
                role_id="risk_controller",
                site_id="deepseek",
                protocol="chat",
                model="model-a",
                prompt_version="ptv1:test",
                input_hash="input-test",
                experiment_id=None,
                variant_id=None,
                status="completed",
                usage={"input_tokens": 100, "output_tokens": 50, "total_tokens": 150},
                duration_ms=10,
                error=None,
                attempt_index=1,
            )
            payload = ai_store.payload()
            payload["sites"][0]["input_cost_per_million_tokens"] = 1.0
            payload["sites"][0]["output_cost_per_million_tokens"] = 2.0
            ai_store.update(payload)

            report = AIGovernanceReporter(store, ai_store).build(
                loop_run_id="loop-reprice",
                debate_id=None,
            )
            source = dict(store.list_ai_invocations(loop_run_id="loop-reprice")[0])

        self.assertEqual(report["summary"]["cost_status"], "known")
        self.assertEqual(report["summary"]["repriced_invocation_count"], 1)
        self.assertAlmostEqual(report["summary"]["estimated_cost_usd"], 0.0002)
        self.assertEqual(source["cost_status"], "unknown")
        self.assertIsNone(source["estimated_cost_usd"])

    def test_budget_guard_blocks_when_conservative_reservation_does_not_fit(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            permit = AIBudgetGuard(store).acquire(
                loop_run_id="loop-a",
                site_id="deepseek",
                system_prompt="x" * 100,
                user_prompt="y" * 100,
                pricing={"input_cost_per_million_tokens": None, "output_cost_per_million_tokens": None},
                policy=AIBudgetPolicy(max_total_tokens_per_loop=100, max_output_tokens_per_call=64),
            )

        self.assertFalse(permit.allowed)
        self.assertIn("token budget exhausted", permit.reason or "")


def _insert_message(store: SQLiteStore) -> None:
    store.insert_ai_debate_message(
        {
            "message_id": "message-a",
            "debate_id": "debate-a",
            "loop_run_id": "loop-a",
            "round_index": 1,
            "turn_index": 1,
            "phase_id": "independent_analysis",
            "message_type": "analysis",
            "reply_to_message_ids": [],
            "agent_role": "evidence_auditor",
            "stance": "evidence",
            "provider": "deepseek",
            "protocol": "chat",
            "model": "model-a",
            "status": "completed",
            "content": {
                "claims": [
                    {"claim_id": "supported", "text": "有证据", "evidence_refs": ["research:1"]},
                    {"claim_id": "unsupported", "text": "无证据", "evidence_refs": []},
                ]
            },
            "error": None,
            "usage": {"total_tokens": 30},
            "duration_ms": 100,
            "prompt_hash": "input-a",
            "prompt_version": "ptv1:test",
            "created_at": "2026-01-01T00:00:00+00:00",
        }
    )


if __name__ == "__main__":
    unittest.main()
