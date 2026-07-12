from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

from finbot.config.ai_sites import AISitesConfigStore
from finbot.council.director import ResearchDirector
from finbot.council.runtime import WorkflowRunService
from finbot.council.workflow_engine import WorkflowNodeContext
from finbot.storage.sqlite_store import SQLiteStore
from finbot.workspace.workflow_versions import WorkflowVersionService


class WorkflowRuntimeTests(unittest.TestCase):
    def test_director_selects_five_scenarios_and_enforces_depth_budget(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            templates = AISitesConfigStore(Path(temp_dir)).council_templates()
        director = ResearchDirector()

        selections = {
            director.plan({"trigger_type": "scheduled-monitor"}, templates)["template_id"],
            director.plan({"query": "深度分析 BTC 并提交投委会"}, templates)["template_id"],
            director.plan({"query": "分析监管公告的事件冲击"}, templates)["template_id"],
            director.plan({"query": "复盘原建议并决定是否退出"}, templates)["template_id"],
            director.plan({"query": "研究 SOL 产品"}, templates)["template_id"],
        }
        deep = director.plan({"query": "深度分析 BTC", "depth": "deep", "rounds": 99}, templates)

        self.assertEqual(
            selections,
            {
                "quick_market_scan",
                "deep_investment_committee",
                "event_impact_analysis",
                "position_review",
                "standard_product_research",
            },
        )
        self.assertEqual(deep["rounds"], 10)
        self.assertEqual(deep["budget_policy"]["max_cost_usd"], 2.0)
        self.assertFalse(deep["policy"]["trading_execution_allowed"])

    def test_dry_run_persists_task_progress_and_redacted_checkpoints(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            service = WorkflowRunService(
                SQLiteStore(root / "finbot.sqlite3"),
                AISitesConfigStore(root),
            )

            run = service.run(
                {
                    "template_id": "standard_product_research",
                    "query": "研究 BTC",
                    "symbol": "BTCUSDT",
                    "evidence_status": "confirmed",
                    "api_key": "must-not-persist",
                    "context": {"hidden_reasoning": "must-not-persist"},
                },
                dry_run=True,
            )
            serialized = json.dumps(run, ensure_ascii=False)

        self.assertEqual(run["status"], "completed")
        self.assertEqual({ledger["ledger_type"] for ledger in run["ledgers"]}, {"task", "progress"})
        self.assertEqual(len(run["checkpoints"]), 9)
        self.assertNotIn("must-not-persist", serialized)
        self.assertNotIn("api_key", run["template_snapshot"]["roles"][0])
        self.assertTrue(all(checkpoint["status"] == "completed" for checkpoint in run["checkpoints"]))

    def test_deep_workflow_waits_and_resumes_from_persisted_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            service = WorkflowRunService(
                SQLiteStore(root / "finbot.sqlite3"),
                AISitesConfigStore(root),
            )
            waiting = service.run(
                {
                    "template_id": "deep_investment_committee",
                    "query": "深度审查 BTC",
                    "symbol": "BTCUSDT",
                    "evidence_status": "empty",
                },
                dry_run=True,
            )
            completed_before_resume = {
                checkpoint["node_id"]: checkpoint["attempt"]
                for checkpoint in waiting["checkpoints"]
                if checkpoint["status"] == "completed"
            }
            resumed = service.resume(
                waiting["workflow_run_id"],
                node_outputs={
                    "node_investment_review": {
                        "approved": True,
                        "reviewer": "operator",
                        "note": "仅批准形成研究结论",
                    }
                },
            )

        self.assertEqual(waiting["status"], "waiting_human")
        self.assertEqual(waiting["result"]["loop_counts"], {"edge_research_loop": 1})
        self.assertEqual(resumed["status"], "completed")
        self.assertEqual(resumed["result"]["outputs"]["node_investment_review"]["approved"], True)
        for node_id, attempt in completed_before_resume.items():
            latest = [item for item in resumed["checkpoints"] if item["node_id"] == node_id][-1]
            self.assertEqual(latest["attempt"], attempt)

    def test_dry_run_uses_requested_workflow_version_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            ai_store = AISitesConfigStore(root)
            version_service = WorkflowVersionService(store, ai_store)
            template = ai_store.council_template("standard_product_research").to_dict()
            template["description"] = "draft-version-snapshot"
            template["roles"][0]["model"] = "draft-only-model"
            draft = version_service.save_draft(template)
            version_id = draft["version"]["workflow_version_id"]

            run = WorkflowRunService(store, ai_store).run(
                {
                    "template_id": "standard_product_research",
                    "query": "研究指定草稿版本",
                    "evidence_status": "confirmed",
                },
                dry_run=True,
                workflow_version_id=version_id,
            )

        self.assertEqual(run["workflow_version_id"], version_id)
        self.assertEqual(run["template_snapshot"]["description"], "draft-version-snapshot")
        self.assertEqual(run["template_snapshot"]["roles"][0]["model"], "draft-only-model")

    def test_replan_policy_records_second_task_ledger_revision(self) -> None:
        def fail_agent(context: WorkflowNodeContext) -> dict[str, Any]:
            raise RuntimeError(f"provider unavailable for {context.node.node_id}")

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            service = WorkflowRunService(
                SQLiteStore(root / "finbot.sqlite3"),
                AISitesConfigStore(root),
                external_node_executor=fail_agent,
            )
            run = service.run(
                {
                    "template_id": "deep_investment_committee",
                    "query": "深度审查 BTC",
                    "symbol": "BTCUSDT",
                    "evidence_status": "confirmed",
                },
                dry_run=False,
            )

        task_ledgers = [ledger for ledger in run["ledgers"] if ledger["ledger_type"] == "task"]
        self.assertEqual(run["status"], "partial")
        self.assertEqual([ledger["revision"] for ledger in task_ledgers], [1, 2])
        self.assertEqual(
            task_ledgers[-1]["payload"]["revisions"][-1]["action"],
            "retry_from_latest_checkpoint_then_stop",
        )


if __name__ == "__main__":
    unittest.main()
