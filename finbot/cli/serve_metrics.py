from __future__ import annotations

import argparse
import time
from pathlib import Path

from prometheus_client import CollectorRegistry, start_http_server

from finbot.observability.metrics import FinBotMetricsCollector
from finbot.storage.sqlite_store import SQLiteStore


def main() -> None:
    parser = argparse.ArgumentParser(description="FinBot Prometheus metrics server")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=9090, type=int)
    args = parser.parse_args()
    store = SQLiteStore(Path(args.data_dir) / "finbot.sqlite3")
    registry = CollectorRegistry(auto_describe=True)
    registry.register(FinBotMetricsCollector(store))
    start_http_server(args.port, addr=args.host, registry=registry)
    print(f"FinBot Metrics 已启动: http://{args.host}:{args.port}/metrics", flush=True)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
