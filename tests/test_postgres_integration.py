from __future__ import annotations

import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from finbot.execution.repository import OmsRepository
from finbot.experiments.registry import ExperimentRegistry
from finbot.storage.postgres_store import PostgresStore
from finbot.storage.sqlite_store import SQLiteStore
from finbot.storage.sqlite_to_postgres import migrate_sqlite_to_postgres


@unittest.skipUnless(os.getenv("FINBOT_TEST_DATABASE_URL"), "需要隔离的 PostgreSQL 测试库")
class PostgresIntegrationTests(unittest.TestCase):
    def test_schema_migration_and_runtime_queue_contract(self) -> None:
        database_url = os.environ["FINBOT_TEST_DATABASE_URL"]
        target = PostgresStore(database_url, min_pool_size=1, max_pool_size=2)
        try:
            with tempfile.TemporaryDirectory(prefix="finbot-postgres-integration-") as temp_dir:
                sqlite_path = Path(temp_dir) / "source.sqlite3"
                source = SQLiteStore(sqlite_path)
                source.init_schema()
                OmsRepository(source)
                ExperimentRegistry(source)

                first_report = migrate_sqlite_to_postgres(sqlite_path, target, batch_size=50)
                second_report = migrate_sqlite_to_postgres(sqlite_path, target, batch_size=50)

            self.assertEqual(first_report["status"], "migrated")
            self.assertGreater(first_report["table_count"], 50)
            self.assertEqual(second_report["status"], "already_migrated")
            OmsRepository(target)
            ExperimentRegistry(target)

            now = datetime.now(timezone.utc)
            request = target.enqueue_autonomous_request(
                {
                    "request_id": "postgres-integration-request",
                    "trigger_type": "manual",
                    "requested_at": now.isoformat(),
                    "available_at": now.isoformat(),
                    "dedupe_key": "postgres-integration-dedupe",
                    "payload": {"source": "integration-test"},
                }
            )
            claimed = target.claim_autonomous_request(
                "postgres-integration-worker",
                now.isoformat(),
                (now + timedelta(minutes=5)).isoformat(),
            )

            self.assertEqual(request["status"], "queued")
            self.assertIsNotNone(claimed)
            self.assertEqual(claimed["status"], "running")
            self.assertEqual(claimed["worker_id"], "postgres-integration-worker")
        finally:
            target.close()


if __name__ == "__main__":
    unittest.main()
