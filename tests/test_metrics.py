from __future__ import annotations

import tempfile
import unittest
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from finbot.observability.metrics import FinBotMetricsCollector
from finbot.storage.sqlite_store import SQLiteStore


class MetricsCollectorTests(unittest.TestCase):
    def test_snapshot_reports_queue_freshness_and_worker_heartbeat(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            timestamp = datetime(2026, 7, 12, tzinfo=timezone.utc).isoformat()
            with store.connect() as connection:
                connection.execute(
                    """
                    insert into autonomous_run_requests (
                      request_id, trigger_type, status, requested_at, available_at,
                      attempt, payload_json, result_json
                    ) values ('request-1', 'manual', 'queued', ?, ?, 0, '{}', '{}')
                    """,
                    (timestamp, timestamp),
                )
                connection.execute(
                    """
                    insert into autonomous_worker_heartbeats (
                      worker_id, status, started_at, heartbeat_at, metadata_json
                    ) values ('worker-1', 'idle', ?, ?, '{}')
                    """,
                    (timestamp, timestamp),
                )
                connection.execute(
                    """
                    insert into raw_evidence (
                      evidence_id, source_id, job_id, fetched_at, success, metadata_json
                    ) values ('evidence-1', 'source-1', 'job-1', ?, 1, '{}')
                    """,
                    (timestamp,),
                )
            now = datetime(2026, 7, 12, 0, 2, tzinfo=timezone.utc).timestamp()
            snapshot = FinBotMetricsCollector(store, clock=lambda: now).snapshot()

        self.assertEqual(snapshot["queue_depth"], 1)
        self.assertEqual(snapshot["data_freshness_seconds"], 120)
        self.assertEqual(snapshot["worker_heartbeat_age_seconds"], 120)

    def test_snapshot_does_not_create_a_missing_database(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database_path = Path(temp_dir) / "missing.sqlite3"
            collector = FinBotMetricsCollector(SQLiteStore(database_path))

            with self.assertRaises(sqlite3.OperationalError):
                collector.snapshot()

            self.assertFalse(database_path.exists())


if __name__ == "__main__":
    unittest.main()
