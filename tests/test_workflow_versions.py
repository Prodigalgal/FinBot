from __future__ import annotations

import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

from finbot.config.ai_sites import AISitesConfigStore
from finbot.storage.sqlite_store import SQLiteStore
from finbot.workspace.workflow_versions import WorkflowVersionService


class WorkflowVersionTests(unittest.TestCase):
    def test_draft_publish_and_rollback_keep_immutable_version_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            ai_store = AISitesConfigStore(root)
            ai_store.update(ai_store.default_payload())
            store = SQLiteStore(root / "data" / "finbot.sqlite3")
            service = WorkflowVersionService(store, ai_store)

            baseline = service.list_versions("product_advisory")
            template = deepcopy(baseline["versions"][0]["content"])
            template["display_name"] = "已发布测试工作流"
            draft = service.save_draft(template, change_note="修改显示名")
            published = service.publish(draft["version"]["workflow_version_id"])
            rolled_back = service.rollback(baseline["versions"][0]["workflow_version_id"])
            versions = service.list_versions("product_advisory")["versions"]

        self.assertEqual(baseline["versions"][0]["status"], "published")
        self.assertEqual(published["version"]["version_number"], 2)
        self.assertEqual(rolled_back["version"]["version_number"], 3)
        self.assertEqual(rolled_back["version"]["status"], "published")
        self.assertEqual(sum(1 for item in versions if item["status"] == "published"), 1)
        self.assertEqual(ai_store.council_template("product_advisory").display_name, baseline["versions"][0]["content"]["display_name"])

    def test_estimate_and_node_validation_are_audited_without_external_call(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            ai_store = AISitesConfigStore(root)
            ai_store.update(ai_store.default_payload())
            service = WorkflowVersionService(SQLiteStore(root / "data" / "finbot.sqlite3"), ai_store)
            template = service.list_versions("product_advisory")["versions"][0]["content"]
            agent_node = next(item for item in template["workflow"]["nodes"] if item["node_type"] == "agent")

            estimate = service.estimate(template, rounds=3)
            test_result = service.test_node(
                template,
                node_id=agent_node["node_id"],
                sample_input={"topic": "BTC", "api_key": "must-not-persist"},
            )
            audit = service.list_versions("product_advisory")["node_tests"][0]

        self.assertGreater(estimate["invocation_count"], 1)
        self.assertGreater(estimate["estimated_total_tokens"], 0)
        self.assertEqual(test_result["test"]["output"]["external_call_sent"], False)
        self.assertEqual(audit["input"]["api_key"], "***")

    def test_invalid_cycle_cannot_be_saved_as_draft(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            ai_store = AISitesConfigStore(root)
            service = WorkflowVersionService(SQLiteStore(root / "data" / "finbot.sqlite3"), ai_store)
            template = deepcopy(service.list_versions("product_advisory")["versions"][0]["content"])
            chair = next(item for item in template["workflow"]["nodes"] if item["node_type"] == "chair")
            input_node = next(item for item in template["workflow"]["nodes"] if item["node_type"] == "input")
            template["workflow"]["edges"].append(
                {
                    "edge_id": "edge_invalid_cycle",
                    "source_node_id": chair["node_id"],
                    "target_node_id": input_node["node_id"],
                }
            )

            with self.assertRaises(ValueError):
                service.save_draft(template)


if __name__ == "__main__":
    unittest.main()
