from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from finbot.autonomous.config import AutonomousLoopConfig
from finbot.autonomous.worker import INSTANT_RESEARCH_TRIGGER, REPLAY_TRIGGER, AutonomousRequestQueue, AutonomousWorker
from finbot.storage.sqlite_store import SQLiteStore


class FakeRunner:
    def run(self, config: AutonomousLoopConfig, trigger_type: str) -> dict[str, Any]:
        return {
            "status": "passed",
            "loop_run_id": f"loop-{trigger_type}",
            "summary": {"failed_steps": [], "first_error": None},
            "output": str(Path(config.data_dir) / "reports" / "autonomous-loop-latest.json"),
        }


class CapturingRunner:
    def __init__(self) -> None:
        self.config: AutonomousLoopConfig | None = None
        self.request_context: dict[str, Any] | None = None
        self.request_id: str | None = None

    def run(
        self,
        config: AutonomousLoopConfig,
        trigger_type: str,
        request_context: dict[str, Any] | None = None,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        self.config = config
        self.request_context = request_context
        self.request_id = request_id
        return {
            "status": "passed",
            "loop_run_id": f"loop-{trigger_type}",
            "summary": {"failed_steps": [], "first_error": None},
            "output": str(Path(config.data_dir) / "reports" / "autonomous-loop-latest.json"),
        }


class AutonomousWorkerTests(unittest.TestCase):
    def test_manual_request_is_claimed_and_completed_by_worker(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            queue = AutonomousRequestQueue(store)
            request = queue.enqueue("manual-test")
            config = AutonomousLoopConfig(data_dir=temp_dir, enabled=False)
            worker = AutonomousWorker(
                config_loader=lambda: config,
                store_loader=lambda _config: store,
                runner_factory=lambda _config: FakeRunner(),  # type: ignore[arg-type]
                worker_id="worker-a",
                poll_seconds=0.1,
                lease_seconds=10,
                heartbeat_seconds=1,
            )

            result = worker.tick()
            snapshot = queue.snapshot()

        self.assertEqual(result["status"], "succeeded")
        self.assertEqual(result["request_id"], request["request_id"])
        self.assertEqual(snapshot["queue"]["succeeded"], 1)
        self.assertEqual(snapshot["recent_requests"][0]["loop_run_id"], "loop-manual-test")
        self.assertEqual(snapshot["workers"][0]["status"], "idle")

    def test_only_lease_owner_can_consume_queue(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            config = AutonomousLoopConfig(data_dir=temp_dir, enabled=False)
            worker_a = AutonomousWorker(
                config_loader=lambda: config,
                store_loader=lambda _config: store,
                runner_factory=lambda _config: FakeRunner(),  # type: ignore[arg-type]
                worker_id="worker-a",
                lease_seconds=30,
            )
            worker_b = AutonomousWorker(
                config_loader=lambda: config,
                store_loader=lambda _config: store,
                runner_factory=lambda _config: FakeRunner(),  # type: ignore[arg-type]
                worker_id="worker-b",
                lease_seconds=30,
            )

            first = worker_a.tick()
            second = worker_b.tick()

        self.assertEqual(first["status"], "idle")
        self.assertEqual(second["status"], "standby")

    def test_expired_request_lease_is_recovered(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            request = AutonomousRequestQueue(store).enqueue("manual-recovery")
            now = datetime.now(timezone.utc)
            claimed = store.claim_autonomous_request(
                "dead-worker",
                now.isoformat(),
                (now - timedelta(seconds=1)).isoformat(),
            )

            recovered = store.claim_autonomous_request(
                "worker-b",
                (now + timedelta(seconds=1)).isoformat(),
                (now + timedelta(seconds=30)).isoformat(),
            )

        self.assertEqual(claimed["request_id"], request["request_id"])
        self.assertEqual(recovered["request_id"], request["request_id"])
        self.assertEqual(recovered["worker_id"], "worker-b")
        self.assertEqual(recovered["attempt"], 2)

    def test_expired_request_reconciles_abandoned_loop_and_pipeline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            queue = AutonomousRequestQueue(store)
            request = queue.enqueue("manual-recovery")
            now = datetime.now(timezone.utc)
            old_started_at = (now - timedelta(minutes=30)).isoformat()
            store.claim_autonomous_request(
                "dead-worker",
                old_started_at,
                (now - timedelta(seconds=1)).isoformat(),
            )
            store.insert_autonomous_loop_run(
                {
                    "loop_run_id": "loop-abandoned",
                    "status": "running",
                    "trigger_type": "manual-recovery",
                    "config": {},
                    "summary": {},
                    "started_at": old_started_at,
                }
            )
            store.link_autonomous_request_loop(request["request_id"], "loop-abandoned")
            store.insert_research_pipeline_run(
                {
                    "run_id": "pipeline-abandoned",
                    "profile": "test",
                    "status": "running",
                    "triggered_by": "autonomous:loop-abandoned",
                    "config": {},
                    "summary": {},
                    "started_at": old_started_at,
                }
            )
            config = AutonomousLoopConfig(data_dir=temp_dir, enabled=False)
            worker = AutonomousWorker(
                config_loader=lambda: config,
                store_loader=lambda _config: store,
                runner_factory=lambda _config: FakeRunner(),  # type: ignore[arg-type]
                worker_id="worker-recovery",
                lease_seconds=10,
            )

            result = worker.tick()
            loop = store.get_autonomous_loop_run("loop-abandoned")
            pipeline = next(
                row
                for row in store.list_research_pipeline_runs(limit=10)
                if row["run_id"] == "pipeline-abandoned"
            )

        self.assertEqual(result["status"], "succeeded")
        self.assertEqual(loop["status"], "abandoned")
        self.assertEqual(loop["error"], "worker_lease_expired")
        self.assertEqual(pipeline["status"], "failed")
        self.assertEqual(pipeline["error"], "parent_loop_abandoned")

    def test_instant_research_is_prioritized_over_regular_queue(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            queue = AutonomousRequestQueue(store)
            queue.enqueue("scheduler")
            instant = queue.enqueue(INSTANT_RESEARCH_TRIGGER, payload={"query": "分析 BTC 流动性"})
            now = datetime.now(timezone.utc)

            claimed = store.claim_autonomous_request(
                "worker-a",
                now.isoformat(),
                (now + timedelta(seconds=30)).isoformat(),
            )

        self.assertEqual(claimed["request_id"], instant["request_id"])

    def test_instant_research_passes_context_and_disables_paper_execution(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            queue = AutonomousRequestQueue(store)
            request = queue.enqueue(
                INSTANT_RESEARCH_TRIGGER,
                payload={"query": "分析比特币 ETF 资金流", "focus_queries": ["BTC ETF inflow latest"]},
            )
            config = AutonomousLoopConfig(
                data_dir=temp_dir,
                paper_execution_enabled=True,
                paper_execution_submit_orders=True,
            )
            runner = CapturingRunner()
            worker = AutonomousWorker(
                config_loader=lambda: config,
                store_loader=lambda _config: store,
                runner_factory=lambda _config: runner,  # type: ignore[arg-type]
                worker_id="worker-instant",
                lease_seconds=30,
            )

            result = worker.tick()

        self.assertEqual(result["status"], "succeeded")
        self.assertEqual(runner.request_id, request["request_id"])
        self.assertEqual(runner.request_context["query"], "分析比特币 ETF 资金流")
        self.assertEqual(runner.config.symbols, ("BTCUSDT",))
        self.assertFalse(runner.config.paper_execution_enabled)
        self.assertFalse(runner.config.paper_execution_submit_orders)

    def test_replay_normalizes_string_sequences_and_disables_order_submission(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            AutonomousRequestQueue(store).enqueue(
                REPLAY_TRIGGER,
                payload={
                    "replay_config": {
                        "symbols": "BTCUSDT, ETHUSDT, BTCUSDT",
                        "providers": ["gate", "bybit"],
                    }
                },
            )
            config = AutonomousLoopConfig(
                data_dir=temp_dir,
                paper_execution_enabled=True,
                paper_execution_submit_orders=True,
            )
            runner = CapturingRunner()
            worker = AutonomousWorker(
                config_loader=lambda: config,
                store_loader=lambda _config: store,
                runner_factory=lambda _config: runner,  # type: ignore[arg-type]
                worker_id="worker-replay",
                lease_seconds=30,
            )

            result = worker.tick()

        self.assertEqual(result["status"], "succeeded")
        self.assertEqual(runner.config.symbols, ("BTCUSDT", "ETHUSDT"))
        self.assertEqual(runner.config.providers, ("gate", "bybit"))
        self.assertFalse(runner.config.paper_execution_submit_orders)
        self.assertTrue(runner.config.paper_execution_require_human_review)


if __name__ == "__main__":
    unittest.main()
