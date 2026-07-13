from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

from finbot.storage.postgres_store import PostgresStore
from finbot.storage.sqlite_to_postgres import migrate_sqlite_to_postgres


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="一次性把 FinBot SQLite 数据迁移到空 PostgreSQL。")
    parser.add_argument("--sqlite-path", required=True)
    parser.add_argument("--database-url-env", default="FINBOT_DATABASE_URL")
    parser.add_argument("--report", default=None)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--connect-attempts", type=int, default=60)
    parser.add_argument("--connect-delay-seconds", type=float, default=2.0)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    database_url = os.getenv(args.database_url_env, "").strip()
    if not database_url:
        raise RuntimeError(f"缺少 PostgreSQL 连接变量：{args.database_url_env}")
    target = _connect_with_retry(
        database_url,
        attempts=max(1, args.connect_attempts),
        delay_seconds=max(0.1, args.connect_delay_seconds),
    )
    try:
        result = migrate_sqlite_to_postgres(
            Path(args.sqlite_path),
            target,
            batch_size=args.batch_size,
        )
    finally:
        target.close()
    if args.report:
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


def _connect_with_retry(database_url: str, *, attempts: int, delay_seconds: float) -> PostgresStore:
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            return PostgresStore(database_url, min_pool_size=1, max_pool_size=2)
        except Exception as exc:
            last_error = exc
            if attempt < attempts:
                time.sleep(delay_seconds)
    raise RuntimeError(f"PostgreSQL 在 {attempts} 次尝试后仍不可用：{type(last_error).__name__}") from last_error


if __name__ == "__main__":
    main()
