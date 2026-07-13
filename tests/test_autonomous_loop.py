from __future__ import annotations

import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from finbot.autonomous.config import AutonomousLoopConfig
from finbot.autonomous.runner import AutonomousResearchLoopRunner, ProxyRuntime as RunnerProxyRuntime
from finbot.autonomous.scheduler import AutonomousLoopScheduler
from finbot.autonomous.step_status import is_failed_step_output
from finbot.autonomous.worker import INSTANT_RESEARCH_TRIGGER, AutonomousRequestQueue
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.storage.sqlite_store import SQLiteStore


class AutonomousLoopTests(unittest.TestCase):
    def test_runner_declares_proxy_runtime_dependency(self) -> None:
        self.assertTrue(callable(RunnerProxyRuntime.from_settings))

    def test_runner_persists_steps_and_recommendations(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config = AutonomousLoopConfig(
                data_dir=temp_dir,
                run_ingestion=False,
                run_ai_compression=False,
                run_followups=False,
                symbols=("BTCUSDT",),
                providers=("binance",),
                intervals=("1h",),
                max_recommendations=3,
                run_ai_debate=False,
            )
            runner = AutonomousResearchLoopRunner(
                research_executor=_research_stub,
                catalog_executor=_catalog_stub,
                universe_executor=_universe_stub,
                operator_executor=_operator_stub,
            )

            report = runner.run(config, trigger_type="unittest")

            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            runs = store.list_autonomous_loop_runs()
            steps = store.latest_autonomous_loop_steps(report["loop_run_id"])
            artifacts = store.list_autonomous_loop_artifacts(report["loop_run_id"])

            self.assertEqual(report["status"], "passed")
            self.assertEqual(runs[0]["loop_run_id"], report["loop_run_id"])
            self.assertEqual(
                [step["step_name"] for step in steps],
                [
                    "research_pipeline",
                    "instrument_catalog",
                    "universe_selection",
                    "operator_workbench",
                    "product_candidates",
                    "ai_debate",
                    "trade_synthesis",
                    "product_selection",
                    "recommendation_evaluation",
                    "portfolio_risk",
                    "execution_robot",
                    "ai_governance",
                    "paper_execution",
                    "publish_status",
                ],
            )
            self.assertGreaterEqual(len(artifacts), 5)
            self.assertEqual(report["recommended_products"][0]["symbol"], "BTCUSDT")
            self.assertEqual(report["decision_readiness"]["status"], "ready")
            self.assertTrue(report["decision_readiness"]["decision_ready"])
            self.assertEqual(report["summary"]["decision_readiness"], report["decision_readiness"])
            self.assertFalse(report["policy"]["execution_allowed"])

    def test_config_reads_autonomous_runtime_values(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = RuntimeConfigStore(Path(temp_dir))
            store.update(
                {
                    "autonomous.enabled": True,
                    "autonomous.interval_minutes": 15,
                    "autonomous.symbols": ["BTCUSDT", "ETHUSDT"],
                    "autonomous.providers": ["binance"],
                    "autonomous.recommendation_min_confidence": 0.6,
                    "autonomous.run_ai_debate": True,
                    "autonomous.workflow_engine_version": 2,
                    "autonomous.workflow_depth": "deep",
                    "autonomous.workflow_director_enabled": True,
                    "autonomous.workflow_learning_enabled": False,
                    "autonomous.ai_debate_rounds": 3,
                    "autonomous.ai_debate_max_candidates": 2,
                    "autonomous.ai_trade_min_confidence": 0.7,
                    "autonomous.ai_trade_require_research_confirmation": False,
                    "execution_robot.enabled": True,
                    "execution_robot.max_output_tokens": 3072,
                    "paper_execution.enabled": True,
                    "paper_execution.submit_orders": True,
                    "paper_execution.adapters": ["gate_testnet", "bybit_demo"],
                    "paper_execution.gate_testnet_api_key": "gate-test-key",
                    "paper_execution.gate_testnet_api_secret": "gate-test-secret",
                }
            )

            config = AutonomousLoopConfig.from_runtime_config(store)

            self.assertTrue(config.enabled)
            self.assertEqual(config.interval_minutes, 15)
            self.assertEqual(config.symbols, ("BTCUSDT", "ETHUSDT"))
            self.assertEqual(config.providers, ("binance",))
            self.assertEqual(config.recommendation_min_confidence, 0.6)
            self.assertTrue(config.run_ai_debate)
            self.assertEqual(config.workflow_engine_version, 2)
            self.assertEqual(config.workflow_depth, "deep")
            self.assertTrue(config.workflow_director_enabled)
            self.assertFalse(config.workflow_learning_enabled)
            self.assertEqual(config.ai_debate_rounds, 3)
            self.assertEqual(config.ai_debate_max_candidates, 2)
            self.assertEqual(config.ai_trade_min_confidence, 0.7)
            self.assertFalse(config.ai_trade_require_research_confirmation)
            self.assertTrue(config.execution_robot_enabled)
            self.assertEqual(config.execution_robot_max_output_tokens, 3072)
            self.assertTrue(config.paper_execution_enabled)
            self.assertTrue(config.paper_execution_submit_orders)
            self.assertEqual(config.paper_execution_adapters, ("gate_testnet", "bybit_demo"))
            safe_config = config.to_dict()
            self.assertTrue(safe_config["paper_execution_credentials_configured"]["gate_testnet"])
            self.assertNotIn("gate_testnet_api_key", safe_config)
            self.assertNotIn("gate_testnet_api_secret", safe_config)
            self.assertNotIn("gate-test-key", str(safe_config))
            self.assertNotIn("gate-test-secret", str(safe_config))

    def test_director_selects_v2_template_for_worker_context(self) -> None:
        config = AutonomousLoopConfig(
            workflow_engine_version=2,
            workflow_depth="standard",
            workflow_director_enabled=True,
        )
        context = {
            "trigger_type": "instant-research",
            "request_context": {"query": "分析监管公告的事件冲击"},
            "product_candidates": {
                "candidates": [
                    {
                        "candidate_id": "btc-linear",
                        "normalized_symbol": "BTCUSDT",
                        "market_type": "linear",
                        "research_context": {"status": "market-confirmed"},
                    }
                ]
            },
        }

        plan = AutonomousResearchLoopRunner._director_plan(config, context)

        self.assertIsNotNone(plan)
        self.assertEqual(plan["template_id"], "event_impact_analysis")
        self.assertEqual(plan["depth"], "standard")
        self.assertEqual(plan["facts"][1]["field"], "product_id")

    def test_config_falls_back_to_global_public_exchange_providers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = RuntimeConfigStore(Path(temp_dir))
            store.update({"exchange.enabled_public_providers": ["gate"]})

            config = AutonomousLoopConfig.from_runtime_config(store)

            self.assertEqual(config.providers, ("gate",))

    def test_paper_execution_treats_empty_execution_robot_as_safe_noop(self) -> None:
        runner = AutonomousResearchLoopRunner()
        config = AutonomousLoopConfig(execution_robot_enabled=True, paper_execution_enabled=True)

        report = runner._run_paper_execution(
            config,
            "loop-empty-robot",
            {
                "execution_robot": {
                    "status": "empty",
                    "summary": {"reasons": ["没有可供执行机器人复核的方向性候选"]},
                }
            },
        )

        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["summary"]["execution_count"], 0)
        self.assertEqual(report["summary"]["reasons"], ["没有可供执行机器人复核的方向性候选"])
        self.assertEqual(report["executions"], [])

    def test_paper_execution_remains_fail_closed_when_execution_robot_fails(self) -> None:
        runner = AutonomousResearchLoopRunner()
        config = AutonomousLoopConfig(execution_robot_enabled=True, paper_execution_enabled=True)

        report = runner._run_paper_execution(
            config,
            "loop-failed-robot",
            {"execution_robot": {"status": "failed"}},
        )

        self.assertEqual(report["status"], "blocked")
        self.assertEqual(report["summary"]["execution_count"], 0)
        self.assertIn("fail-closed", report["summary"]["reasons"][0])

    def test_policy_blocks_are_successful_no_trade_outcomes(self) -> None:
        runner = AutonomousResearchLoopRunner()
        config = AutonomousLoopConfig(execution_robot_enabled=True, paper_execution_enabled=True)
        robot_report = {
            "status": "blocked",
            "summary": {"reasons": ["Portfolio Risk 门禁未通过"]},
        }

        paper_report = runner._run_paper_execution(
            config,
            "loop-risk-blocked",
            {"execution_robot": robot_report},
        )

        self.assertEqual(paper_report["status"], "blocked")
        self.assertEqual(paper_report["summary"]["execution_count"], 0)
        self.assertFalse(is_failed_step_output("execution_robot", robot_report))
        self.assertFalse(is_failed_step_output("paper_execution", paper_report))
        self.assertTrue(
            is_failed_step_output(
                "paper_execution",
                {
                    "status": "blocked",
                    "summary": {"execution_count": 1, "reasons": []},
                    "executions": [{"status": "blocked_adapter"}],
                },
            )
        )

    def test_runner_links_instant_request_before_completion_and_persists_query(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            request = AutonomousRequestQueue(store).enqueue(
                INSTANT_RESEARCH_TRIGGER,
                payload={"query": "分析 BTC ETF 资金流"},
            )
            now = datetime.now(timezone.utc)
            store.claim_autonomous_request(
                "worker-instant",
                now.isoformat(),
                (now + timedelta(seconds=30)).isoformat(),
            )
            config = AutonomousLoopConfig(
                data_dir=temp_dir,
                run_ai_debate=False,
                paper_execution_enabled=False,
            )
            runner = AutonomousResearchLoopRunner(
                research_executor=_research_stub,
                catalog_executor=_catalog_stub,
                universe_executor=_universe_stub,
                operator_executor=_operator_stub,
            )

            report = runner.run(
                config,
                trigger_type=INSTANT_RESEARCH_TRIGGER,
                request_context={"mode": INSTANT_RESEARCH_TRIGGER, "query": "分析 BTC ETF 资金流"},
                request_id=str(request["request_id"]),
            )
            linked = store.get_autonomous_request(str(request["request_id"]))
            run = store.get_autonomous_loop_run(report["loop_run_id"])

        self.assertEqual(linked["loop_run_id"], report["loop_run_id"])
        self.assertIn("分析 BTC ETF 资金流", run["config_json"])
        self.assertEqual(report["summary"]["request_query"], "分析 BTC ETF 资金流")

    def test_scheduler_rejects_parallel_manual_runs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            config = AutonomousLoopConfig(data_dir=temp_dir, interval_minutes=1, run_ai_debate=False)

            def runner_factory(_config: AutonomousLoopConfig) -> AutonomousResearchLoopRunner:
                return AutonomousResearchLoopRunner(
                    research_executor=_slow_research_stub,
                    catalog_executor=_catalog_stub,
                    universe_executor=_universe_stub,
                    operator_executor=_operator_stub,
                )

            scheduler = AutonomousLoopScheduler(lambda: config, runner_factory=runner_factory, poll_seconds=1)
            accepted = scheduler.trigger_now("unittest")
            try:
                self.assertEqual(accepted["status"], "accepted")
                with self.assertRaises(RuntimeError):
                    scheduler.trigger_now("unittest")
            finally:
                scheduler.stop(timeout_seconds=10.0)


def _research_stub(_config: AutonomousLoopConfig, _loop_run_id: str) -> dict[str, Any]:
    return {"status": "passed", "run_id": "research-run-a", "summary": {"ok": True}, "steps": []}


def _slow_research_stub(_config: AutonomousLoopConfig, _loop_run_id: str) -> dict[str, Any]:
    time.sleep(0.5)
    return _research_stub(_config, _loop_run_id)


def _operator_stub(_config: AutonomousLoopConfig, _loop_run_id: str) -> dict[str, Any]:
    return {
        "status": "ok",
        "report_id": "operator-report-a",
        "items": [
            {
                "status": "ok",
                "provider": "binance",
                "market_type": "spot",
                "symbol": "BTCUSDT",
                "advice": {
                    "provider": "binance",
                    "market_type": "spot",
                    "symbol": "BTCUSDT",
                    "normalized_symbol": "BTCUSDT",
                    "action": "BUY",
                    "confidence": 0.72,
                    "horizon": "1h-context",
                    "levels": {
                        "entry_reference": 100.0,
                        "target_price": 104.0,
                        "invalidation_price": 98.0,
                        "risk_distance_pct": 2.0,
                        "reward_risk_ratio": 2.0,
                    },
                    "rationale": ["unit-test"],
                    "risk_warnings": [],
                    "research_context": {
                        "source": "phase4.1-research-council",
                        "status": "passed",
                        "council_id": "council-a",
                        "pipeline_run_id": "research-run-a",
                        "matched_items_count": 1,
                        "matched_items": [{"event_key": "btc"}],
                    },
                },
            }
        ],
    }


def _catalog_stub(_config: AutonomousLoopConfig, _loop_run_id: str) -> dict[str, Any]:
    return {"status": "passed", "instrument_count": 1, "active_count": 1, "markets": []}


def _universe_stub(_config: AutonomousLoopConfig, loop_run_id: str) -> dict[str, Any]:
    return {
        "status": "passed",
        "universe_run_id": "universe-a",
        "loop_run_id": loop_run_id,
        "summary": {"selected_count": 1},
        "instruments": [
            {
                "instrument_id": "instrument-btc",
                "provider": "binance",
                "market_type": "spot",
                "symbol": "BTCUSDT",
                "normalized_symbol": "BTCUSDT",
                "base_asset": "BTC",
            }
        ],
    }


if __name__ == "__main__":
    unittest.main()
