from __future__ import annotations

import argparse
import signal
from pathlib import Path

from finbot.autonomous.config import AutonomousLoopConfig
from finbot.autonomous.worker import AutonomousWorker
from finbot.cli.common import build_store
from finbot.config.paths import runtime_root
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.observability.logging import configure_logging


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="启动 FinBot 常驻自动循环 Worker。")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--poll-seconds", type=float, default=None)
    parser.add_argument("--lease-seconds", type=float, default=None)
    parser.add_argument("--heartbeat-seconds", type=float, default=None)
    parser.add_argument("--worker-id", default=None)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    logger = configure_logging("worker")
    runtime_store = RuntimeConfigStore(runtime_root(Path.cwd()))

    def load_config() -> AutonomousLoopConfig:
        return AutonomousLoopConfig.from_runtime_config(
            runtime_store,
            data_dir=args.data_dir,
            catalog_path=args.catalog,
            topics_path=args.topics,
        )

    worker = AutonomousWorker(
        config_loader=load_config,
        store_loader=lambda config: build_store(config.data_dir)[1],
        worker_id=args.worker_id,
        poll_seconds=args.poll_seconds or float(runtime_store.value("worker.poll_seconds", 2.0)),
        lease_seconds=args.lease_seconds or float(runtime_store.value("worker.lease_seconds", 30.0)),
        heartbeat_seconds=args.heartbeat_seconds or float(runtime_store.value("worker.heartbeat_seconds", 5.0)),
    )

    def stop_worker(_signum: int, _frame: object) -> None:
        worker.stop()

    signal.signal(signal.SIGINT, stop_worker)
    signal.signal(signal.SIGTERM, stop_worker)
    logger.info(
        "worker_ready",
        extra={"event": "worker_ready", "component": "worker", "status": worker.worker_id},
    )
    worker.run_forever()
    logger.info("worker_stopped", extra={"event": "worker_stopped", "component": "worker"})


if __name__ == "__main__":
    main()
