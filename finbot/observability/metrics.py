from __future__ import annotations

import sqlite3
import time
from datetime import datetime, timezone
from typing import Any, Callable, Iterable

from prometheus_client.core import GaugeMetricFamily
from psycopg import Error as PostgresError

from finbot.storage.sqlite_store import SQLiteStore


class FinBotMetricsCollector:
    def __init__(self, store: SQLiteStore, *, clock: Callable[[], float] = time.time):
        self.store = store
        self.clock = clock

    def collect(self) -> Iterable[GaugeMetricFamily]:
        up = GaugeMetricFamily("finbot_up", "FinBot metrics collector health")
        collector_errors = GaugeMetricFamily("finbot_metrics_collector_errors", "Metrics collection errors")
        try:
            metrics = self.snapshot()
        except (OSError, RuntimeError, ValueError, TypeError, sqlite3.Error, PostgresError):
            up.add_metric([], 0)
            collector_errors.add_metric([], 1)
            yield up
            yield collector_errors
            return
        up.add_metric([], 1)
        collector_errors.add_metric([], 0)
        yield up
        yield collector_errors
        yield _gauge("finbot_autonomous_queue_depth", "Queued autonomous requests", metrics["queue_depth"])
        yield _gauge("finbot_autonomous_running", "Running autonomous loops", metrics["running_loops"])
        yield _gauge("finbot_source_unhealthy", "Non healthy information sources", metrics["unhealthy_sources"])
        yield _gauge("finbot_data_freshness_seconds", "Age of latest successful evidence", metrics["data_freshness_seconds"])
        yield _gauge("finbot_worker_heartbeat_age_seconds", "Age of latest worker heartbeat", metrics["worker_heartbeat_age_seconds"])
        yield _labeled_gauge("finbot_oms_orders", "OMS orders by status", "status", metrics["oms_orders"])
        yield _labeled_gauge("finbot_paper_executions", "Paper executions by status", "status", metrics["paper_executions"])

    def snapshot(self) -> dict[str, Any]:
        now = float(self.clock())
        if getattr(self.store, "backend", "sqlite") == "sqlite" and not self.store.path.exists():
            raise sqlite3.OperationalError(f"Database does not exist: {self.store.path}")
        with self.store.connect() as connection:
            queue_depth = _count(
                connection,
                "select count(*) from autonomous_run_requests where status in ('queued', 'claimed', 'running')",
            )
            running_loops = _count(
                connection,
                "select count(*) from autonomous_loop_runs where status = 'running'",
            )
            unhealthy_sources = _count(
                connection,
                "select count(*) from source_health where status not in ('healthy', 'ok', 'ready')",
            )
            latest_evidence = _scalar(
                connection,
                "select max(fetched_at) from raw_evidence where success = 1",
            )
            latest_heartbeat = _scalar(
                connection,
                "select max(heartbeat_at) from autonomous_worker_heartbeats",
            )
            oms_orders = _group_counts(connection, "oms_orders") if _table_exists(self.store, connection, "oms_orders") else {}
            paper_executions = _group_counts(connection, "paper_executions")
        return {
            "queue_depth": queue_depth,
            "running_loops": running_loops,
            "unhealthy_sources": unhealthy_sources,
            "data_freshness_seconds": _age_seconds(latest_evidence, now),
            "worker_heartbeat_age_seconds": _age_seconds(latest_heartbeat, now),
            "oms_orders": oms_orders,
            "paper_executions": paper_executions,
        }


def _gauge(name: str, documentation: str, value: float) -> GaugeMetricFamily:
    metric = GaugeMetricFamily(name, documentation)
    metric.add_metric([], value)
    return metric


def _labeled_gauge(name: str, documentation: str, label: str, values: dict[str, int]) -> GaugeMetricFamily:
    metric = GaugeMetricFamily(name, documentation, labels=[label])
    for key, value in sorted(values.items()):
        metric.add_metric([key], value)
    return metric


def _count(connection: Any, query: str) -> int:
    return int(connection.execute(query).fetchone()[0])


def _scalar(connection: Any, query: str) -> Any:
    return connection.execute(query).fetchone()[0]


def _group_counts(connection: Any, table: str) -> dict[str, int]:
    return {
        str(row["status"]): int(row["count"])
        for row in connection.execute(f"select status, count(*) as count from {table} group by status")
    }


def _table_exists(store: SQLiteStore, connection: Any, table: str) -> bool:
    if getattr(store, "backend", "sqlite") == "postgresql":
        return connection.execute(
            "select 1 from information_schema.tables where table_schema = current_schema() and table_name = ?",
            (table,),
        ).fetchone() is not None
    return connection.execute(
        "select 1 from sqlite_master where type = 'table' and name = ?",
        (table,),
    ).fetchone() is not None


def _age_seconds(value: Any, now: float) -> float:
    if not value:
        return -1.0
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return max(0.0, now - parsed.timestamp())
