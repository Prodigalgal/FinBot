from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.web.service import FinBotWebApp, _compact_instant_step, create_fastapi_app


def _credential_fingerprint(api_key: str, api_secret: str) -> str:
    return hashlib.sha256(f"{api_key}\0{api_secret}".encode("utf-8")).hexdigest()[:16]


class WebServiceTests(unittest.TestCase):
    def test_decision_review_api_enforces_gate_and_optimistic_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            _seed_reviewable_decision(state.autonomous_store())
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                inbox = client.get("/api/v1/decision-reviews", params={"status": "pending"})
                approved = client.put(
                    "/api/v1/decision-reviews/decision-api",
                    json={"status": "approved", "note": "人工确认", "expected_version": 0},
                )
                stale = client.put(
                    "/api/v1/decision-reviews/decision-api",
                    json={"status": "rejected", "note": "过期写入", "expected_version": 0},
                )

        self.assertEqual(inbox.status_code, 200)
        self.assertEqual(inbox.json()["items"][0]["review"]["status"], "pending")
        self.assertTrue(inbox.json()["items"][0]["approval_eligible"])
        self.assertEqual(approved.status_code, 200)
        self.assertEqual(approved.json()["review"]["status"], "approved")
        self.assertEqual(stale.status_code, 409)

    def test_autonomous_status_keeps_latest_result_snapshot_consistent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            data_dir = root / "data"
            state = FinBotWebApp(
                data_dir=str(data_dir),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            store = state.autonomous_store()
            readiness = {
                "status": "needs-followup",
                "decision_ready": False,
                "simulation_eligible": False,
                "human_review_required": True,
                "reasons": ["unconfirmed_research"],
                "valid_market_data_count": 1,
                "insufficient_market_data_count": 0,
                "recommendation_count": 1,
                "directional_recommendation_count": 0,
                "unconfirmed_research_count": 1,
                "research_statuses": {"needs-followup": 1},
            }
            store.insert_autonomous_loop_run(
                {
                    "loop_run_id": "completed-run",
                    "status": "passed",
                    "trigger_type": "scheduler",
                    "config": {},
                    "summary": {"decision_readiness": readiness},
                    "started_at": "2026-07-11T01:00:00+00:00",
                    "finished_at": "2026-07-11T01:05:00+00:00",
                }
            )
            store.insert_autonomous_loop_run(
                {
                    "loop_run_id": "running-run",
                    "status": "running",
                    "trigger_type": "scheduler",
                    "config": {},
                    "summary": {"steps": ["research_pipeline"]},
                    "started_at": "2026-07-11T02:00:00+00:00",
                }
            )
            reports_dir = data_dir / "reports"
            reports_dir.mkdir(parents=True, exist_ok=True)
            reports_dir.joinpath("autonomous-loop-latest.json").write_text(
                json.dumps(
                    {
                        "status": "passed",
                        "loop_run_id": "completed-run",
                        "decision_readiness": readiness,
                        "recommended_products": [{"symbol": "BTCUSDT", "action": "WATCH"}],
                    }
                ),
                encoding="utf-8",
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                response = client.get("/api/v1/autonomous/status")

        payload = response.json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["recent_runs"][0]["loop_run_id"], "running-run")
        self.assertEqual(payload["latest_result_loop_run_id"], "completed-run")
        self.assertEqual(payload["latest_decision_readiness"], readiness)
        completed = next(run for run in payload["recent_runs"] if run["loop_run_id"] == "completed-run")
        self.assertEqual(completed["decision_readiness"], readiness)

    def test_instant_step_projection_omits_unrendered_payloads(self) -> None:
        step = _compact_instant_step(
            {
                "step_id": "step-a",
                "step_name": "product_selection",
                "status": "passed",
                "input": {"large_context": "not-returned"},
                "output": {
                    "status": "passed",
                    "run_id": "run-a",
                    "recommended_products": {"count": 3, "sample": [{"symbol": "BTCUSDT"}]},
                    "large_report": {"rows": list(range(100))},
                },
            }
        )

        self.assertEqual(step["input"], {})
        self.assertEqual(step["output"]["recommended_products"], {"count": 3})
        self.assertNotIn("large_report", step["output"])

    def test_run_now_uses_worker_queue_and_exposes_catalog_api(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            data_dir = root / "data"
            state = FinBotWebApp(
                data_dir=str(data_dir),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                submitted = client.post(
                    "/api/v1/autonomous/run-now",
                    json={"trigger_type": "api-test"},
                )
                status = client.get("/api/v1/autonomous/status")
                instruments = client.get("/api/v1/instruments")
                evaluation = client.get("/api/v1/evaluations/recommendations/latest")
                portfolio_risk = client.get("/api/v1/portfolio-risk/latest")
                governance = client.get("/api/v1/ai/governance/latest")
                paper_execution = client.get("/api/v1/paper-execution/status")

        self.assertEqual(submitted.status_code, 202)
        self.assertEqual(submitted.json()["mode"], "worker")
        self.assertEqual(status.status_code, 200)
        self.assertEqual(status.json()["scheduler"]["mode"], "worker")
        self.assertEqual(status.json()["worker"]["queue"]["queued"], 1)
        self.assertEqual(instruments.status_code, 200)
        self.assertEqual(instruments.json()["instruments"], [])
        self.assertEqual(evaluation.status_code, 200)
        self.assertEqual(evaluation.json()["status"], "empty")
        self.assertEqual(portfolio_risk.status_code, 200)
        self.assertEqual(governance.status_code, 200)
        self.assertEqual(paper_execution.status_code, 200)
        self.assertEqual(len(paper_execution.json()["adapters"]), 2)
        self.assertFalse(paper_execution.json()["policy"]["real_trading_allowed"])

    def test_instant_research_api_persists_queued_session(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                submitted = client.post(
                    "/api/v1/instant-research",
                    json={
                        "query": "分析 BTC ETF 资金流与风险",
                        "symbols": ["btcusdt"],
                        "product_id": "product-btc-usdt",
                        "preferred_instrument_id": "gate-perpetual-btc",
                        "watchlist_id": "watchlist-local-default",
                        "provider": "gate",
                        "market_type": "perpetual",
                    },
                )
                session_id = submitted.json()["session"]["session_id"]
                detail = client.get(f"/api/v1/instant-research/{session_id}")
                sessions = client.get("/api/v1/instant-research")
                invalid = client.post("/api/v1/instant-research", json={"query": "  "})

        self.assertEqual(submitted.status_code, 202)
        self.assertEqual(detail.status_code, 200)
        self.assertEqual(detail.json()["status"], "queued")
        self.assertEqual(detail.json()["stage"], "queued")
        self.assertEqual(detail.json()["query"], "分析 BTC ETF 资金流与风险")
        self.assertEqual(detail.json()["product_context"]["product_id"], "product-btc-usdt")
        self.assertEqual(detail.json()["product_context"]["provider"], "gate")
        self.assertFalse(detail.json()["policy"]["paper_execution_allowed"])
        self.assertEqual(sessions.json()["count"], 1)
        self.assertEqual(sessions.json()["sessions"][0]["session_id"], session_id)
        self.assertEqual(sessions.json()["sessions"][0]["progress"]["total_steps"], 13)
        self.assertNotIn("loop_run", sessions.json()["sessions"][0])
        self.assertNotIn("debate", sessions.json()["sessions"][0])
        self.assertEqual(invalid.status_code, 400)

    def test_paper_execution_status_exposes_matching_probe_without_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            data_dir = root / "data"
            config_store = RuntimeConfigStore(root)
            gate_key, gate_secret = "gate-test-key", "gate-test-secret"
            bybit_key, bybit_secret = "bybit-demo-key", "bybit-demo-secret"
            config_store.update(
                {
                    "paper_execution.enabled": True,
                    "paper_execution.submit_orders": False,
                    "paper_execution.gate_testnet_api_key": gate_key,
                    "paper_execution.gate_testnet_api_secret": gate_secret,
                    "paper_execution.bybit_demo_api_key": bybit_key,
                    "paper_execution.bybit_demo_api_secret": bybit_secret,
                }
            )
            reports_dir = data_dir / "reports"
            reports_dir.mkdir(parents=True)
            reports_dir.joinpath("paper-exchange-credential-probe.json").write_text(
                json.dumps(
                    {
                        "generated_at": "2026-07-11T00:00:00+00:00",
                        "targets": {
                            "gate_testnet": {
                                "status": "failed",
                                "credential_fingerprint": _credential_fingerprint(gate_key, gate_secret),
                                "attempts": [
                                    {
                                        "status": "failed",
                                        "http_status": 401,
                                        "code": "INVALID_KEY",
                                        "message": "Invalid key provided",
                                    }
                                ],
                            },
                            "bybit_demo": {
                                "status": "passed",
                                "credential_fingerprint": _credential_fingerprint(bybit_key, bybit_secret),
                                "attempts": [{"status": "passed", "result_count": 0}],
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )
            state = FinBotWebApp(
                data_dir=str(data_dir),
                config_store=config_store,
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                response = client.get("/api/v1/paper-execution/status")

        payload = response.json()
        adapters = {adapter["adapter_id"]: adapter for adapter in payload["adapters"]}
        self.assertEqual(adapters["gate_testnet"]["status"], "blocked")
        self.assertFalse(adapters["gate_testnet"]["credentials_verified"])
        self.assertEqual(adapters["bybit_demo"]["status"], "ready")
        self.assertTrue(adapters["bybit_demo"]["credentials_verified"])
        serialized = response.text
        self.assertNotIn(gate_key, serialized)
        self.assertNotIn(gate_secret, serialized)
        self.assertNotIn(bybit_key, serialized)
        self.assertNotIn(bybit_secret, serialized)

    def test_ai_config_api_persists_council_role_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                current = client.get("/api/v1/ai/config").json()
                council_templates = deepcopy(current["council_templates"])
                council_templates[0]["roles"][0]["display_name"] = "API 看多角色"
                response = client.put(
                    "/api/v1/ai/config",
                    json={
                        "sites": current["sites"],
                        "task_bindings": current["task_bindings"],
                        "prompts": current["prompts"],
                        "council_templates": council_templates,
                    },
                )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json()["council_templates"][0]["roles"][0]["display_name"],
            "API 看多角色",
        )

    def test_duplicate_council_role_id_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                current = client.get("/api/v1/ai/config").json()
                council_templates = deepcopy(current["council_templates"])
                council_templates[0]["roles"][1]["role_id"] = council_templates[0]["roles"][0]["role_id"]
                response = client.put(
                    "/api/v1/ai/config",
                    json={
                        "sites": current["sites"],
                        "task_bindings": current["task_bindings"],
                        "prompts": current["prompts"],
                        "council_templates": council_templates,
                    },
                )

        self.assertEqual(response.status_code, 400)
        self.assertIn("role_id must be unique", response.json()["detail"])

    def test_ai_config_exposes_role_presets_and_persists_experiment(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                current = client.get("/api/v1/ai/config").json()
                response = client.put(
                    "/api/v1/ai/config",
                    json={
                        "sites": current["sites"],
                        "task_bindings": current["task_bindings"],
                        "prompts": current["prompts"],
                        "council_templates": current["council_templates"],
                        "experiments": [
                            {
                                "experiment_id": "debate-ab",
                                "display_name": "Debate A/B",
                                "task_id": "ai_debate",
                                "enabled": False,
                                "variants": [
                                    {"variant_id": "control", "weight": 1},
                                    {"variant_id": "challenger", "weight": 1},
                                ],
                            }
                        ],
                    },
                )

        self.assertGreaterEqual(len(current["role_presets"]), 7)
        self.assertNotIn("api_key", current["role_presets"][0])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["experiments"][0]["experiment_id"], "debate-ab")

    def test_setup_api_exposes_defaults_and_applies_recommended_profile(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config_store = RuntimeConfigStore(root)
            config_store.update({"autonomous.interval_minutes": 99})
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=config_store,
                ai_config_store=AISitesConfigStore(root),
            )
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                current = client.get("/api/v1/setup")
                applied = client.post(
                    "/api/v1/setup/apply",
                    json={"profile_id": "recommended", "preserve_existing": True},
                )
                invalid = client.post(
                    "/api/v1/setup/apply",
                    json={"profile_id": "missing", "preserve_existing": True},
                )
                autonomous_enabled = config_store.value("autonomous.enabled")
                interval_minutes = config_store.value("autonomous.interval_minutes")
                cost_budget = config_store.value("autonomous.ai_budget_max_cost_usd_per_loop")

        self.assertEqual(current.status_code, 200)
        self.assertEqual(len(current.json()["profiles"]), 3)
        self.assertGreater(current.json()["defaults"]["runtime_default_value_count"], 40)
        self.assertEqual(applied.status_code, 200)
        self.assertEqual(applied.json()["application"]["status"], "applied")
        self.assertIn("autonomous.interval_minutes", applied.json()["application"]["preserved_keys"])
        self.assertTrue(autonomous_enabled)
        self.assertEqual(interval_minutes, 99)
        self.assertEqual(cost_budget, 0.5)
        self.assertEqual(invalid.status_code, 400)


def _seed_reviewable_decision(store: object) -> None:
    store.init_schema()
    store.insert_autonomous_loop_run(
        {
            "loop_run_id": "loop-api",
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
            "started_at": "2026-07-11T00:00:00+00:00",
            "finished_at": "2026-07-11T00:01:00+00:00",
        }
    )
    store.insert_ai_trade_decision(
        {
            "decision_id": "decision-api",
            "loop_run_id": "loop-api",
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
            "created_at": "2026-07-11T00:01:00+00:00",
        }
    )
    store.insert_portfolio_risk_report(
        {
            "risk_report_id": "risk-api",
            "loop_run_id": "loop-api",
            "status": "passed",
            "config": {},
            "summary": {"risk_status": "passed"},
            "risk_gate": {"status": "passed"},
            "created_at": "2026-07-11T00:01:00+00:00",
        }
    )
    store.insert_ai_governance_report(
        {
            "governance_report_id": "governance-api",
            "loop_run_id": "loop-api",
            "status": "passed",
            "config": {},
            "summary": {"governance_status": "passed"},
            "created_at": "2026-07-11T00:01:00+00:00",
        }
    )


if __name__ == "__main__":
    unittest.main()
