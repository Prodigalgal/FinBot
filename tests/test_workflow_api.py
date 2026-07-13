from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.web.service import FinBotWebApp, create_fastapi_app


class WorkflowApiTests(unittest.TestCase):
    def test_all_builtin_templates_save_estimate_node_test_and_dry_run(self) -> None:
        template_ids = {
            "quick_market_scan",
            "standard_product_research",
            "deep_investment_committee",
            "event_impact_analysis",
            "position_review",
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)
            with TestClient(app) as client:
                templates = {
                    item["template_id"]: item
                    for item in client.get("/api/v1/ai/config").json()["council_templates"]
                    if item["template_id"] in template_ids
                }
                results = {}
                for template_id, template in templates.items():
                    draft = client.post(
                        "/api/v1/workflows/drafts",
                        json={"template": template, "change_note": "builtin acceptance test"},
                    )
                    estimate = client.post(
                        "/api/v1/workflows/estimate",
                        json={"template": template, "rounds": template["round_policy"]["default_rounds"]},
                    )
                    test_node = next(node for node in template["workflow"]["nodes"] if node["node_type"] != "input")
                    node_test = client.post(
                        "/api/v1/workflows/node-test",
                        json={"template": template, "node_id": test_node["node_id"], "sample_input": {"symbol": "BTCUSDT"}},
                    )
                    run = client.post(
                        "/api/v1/workflows/runs",
                        json={
                            "template_id": template_id,
                            "query": "内置模板验收",
                            "symbol": "BTCUSDT",
                            "evidence_status": "empty" if template_id == "deep_investment_committee" else "confirmed",
                            "dry_run": True,
                        },
                    )
                    if run.json()["status"] == "waiting_human":
                        run = client.post(
                            f"/api/v1/workflows/runs/{run.json()['workflow_run_id']}/resume",
                            json={"node_outputs": {"node_investment_review": {"approved": True}}},
                        )
                    results[template_id] = {
                        "draft": draft.status_code,
                        "estimate": estimate.status_code,
                        "node_test": node_test.status_code,
                        "run": run.status_code,
                        "run_status": run.json()["status"],
                    }

        self.assertEqual(set(templates), template_ids)
        self.assertTrue(all(result["draft"] == 200 for result in results.values()))
        self.assertTrue(all(result["estimate"] == 200 for result in results.values()))
        self.assertTrue(all(result["node_test"] == 200 for result in results.values()))
        self.assertTrue(all(result["run"] == 200 for result in results.values()))
        self.assertTrue(all(result["run_status"] == "completed" for result in results.values()))

    def test_schema_director_run_resume_and_learning_endpoints(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                schema = client.get("/api/v1/workflows/schema")
                plan = client.post(
                    "/api/v1/workflows/director/plan",
                    json={"query": "分析监管公告对 BTC 的事件冲击", "symbol": "BTCUSDT"},
                )
                waiting = client.post(
                    "/api/v1/workflows/runs",
                    json={
                        "template_id": "deep_investment_committee",
                        "query": "深度审查 BTC",
                        "symbol": "BTCUSDT",
                        "evidence_status": "empty",
                        "dry_run": True,
                    },
                )
                workflow_run_id = waiting.json()["workflow_run_id"]
                resumed = client.post(
                    f"/api/v1/workflows/runs/{workflow_run_id}/resume",
                    json={
                        "node_outputs": {
                            "node_investment_review": {"approved": True, "reviewer": "operator"}
                        }
                    },
                )
                detail = client.get(f"/api/v1/workflows/runs/{workflow_run_id}")
                runs = client.get("/api/v1/workflows/runs")
                learning = client.get("/api/v1/workflows/learning")
                ai_config = client.get("/api/v1/ai/config")

        self.assertEqual(schema.status_code, 200)
        self.assertEqual(schema.json()["latest_version"], 2)
        self.assertEqual(len(schema.json()["node_types"]), 9)
        self.assertEqual(len(schema.json()["templates"]), 6)
        self.assertEqual(schema.json()["reasoning_efforts"][-2:], ["xhigh", "max"])
        self.assertFalse(schema.json()["credential_policy"]["raw_key_in_workflow_allowed"])
        self.assertEqual(plan.status_code, 200)
        self.assertEqual(plan.json()["template_id"], "event_impact_analysis")
        self.assertEqual(waiting.status_code, 200)
        self.assertEqual(waiting.json()["status"], "waiting_human")
        self.assertEqual(resumed.status_code, 200)
        self.assertEqual(resumed.json()["status"], "completed")
        self.assertEqual(detail.json()["workflow_run_id"], workflow_run_id)
        self.assertEqual(runs.json()["count"], 1)
        self.assertEqual(learning.status_code, 200)
        self.assertEqual(learning.json()["memory_count"], 0)
        self.assertIn("workflow_schema", ai_config.json())
        self.assertIn("learning_summary", ai_config.json())
        self.assertNotIn('"api_key":', ai_config.text)


if __name__ == "__main__":
    unittest.main()
