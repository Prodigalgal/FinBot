from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from finbot.storage.postgres_store import PostgresConnection, PostgresStore, translate_schema_sql


SQLITE_CUTOVER_MIGRATION_ID = "009-sqlite-to-postgresql"
_IDENTIFIER = re.compile(r"^[a-z_][a-z0-9_]*$")


def migrate_sqlite_to_postgres(
    sqlite_path: Path,
    target: PostgresStore,
    *,
    batch_size: int = 500,
) -> dict[str, Any]:
    source_path = sqlite_path.resolve()
    if not source_path.is_file():
        raise FileNotFoundError(f"SQLite 迁移源不存在：{source_path}")
    if batch_size < 1:
        raise ValueError("batch_size 必须大于 0")

    target.init_schema()
    source = sqlite3.connect(f"file:{source_path.as_posix()}?mode=ro", uri=True)
    source.row_factory = sqlite3.Row
    try:
        source.execute("begin")
        with target.connect() as destination:
            destination.execute("begin immediate")
            existing = destination.execute(
                "select * from finbot_schema_migrations where migration_id = ?",
                (SQLITE_CUTOVER_MIGRATION_ID,),
            ).fetchone()
            if existing is not None:
                return {
                    "status": "already_migrated",
                    "migration_id": SQLITE_CUTOVER_MIGRATION_ID,
                    "source_fingerprint": str(existing["source_fingerprint"]),
                    "table_counts": json.loads(str(existing["table_counts_json"])),
                    "applied_at": str(existing["applied_at"]),
                }

            source_fingerprint = _sha256(source_path)
            tables = _source_tables(source)
            source_counts = {table: _source_count(source, table) for table in tables}
            _ensure_source_schema(source, destination)
            _require_empty_target(destination, source_counts)
            copied_counts: dict[str, int] = {}
            for table in tables:
                copied_counts[table] = _copy_table(source, destination, table, batch_size)
            target_counts = {table: _target_count(destination, table) for table in tables}
            mismatches = {
                table: {"source": source_counts[table], "copied": copied_counts[table], "target": target_counts[table]}
                for table in tables
                if source_counts[table] != copied_counts[table] or source_counts[table] != target_counts[table]
            }
            if mismatches:
                raise RuntimeError(f"PostgreSQL 迁移计数不一致：{json.dumps(mismatches, ensure_ascii=False)}")

            _reset_sequences(destination, tables)
            applied_at = _now()
            destination.execute(
                """
                insert into finbot_schema_migrations (
                  migration_id, source_kind, source_fingerprint, table_counts_json, applied_at
                ) values (?, 'sqlite', ?, ?, ?)
                """,
                (
                    SQLITE_CUTOVER_MIGRATION_ID,
                    source_fingerprint,
                    json.dumps(target_counts, ensure_ascii=False, sort_keys=True),
                    applied_at,
                ),
            )
        source.rollback()
    finally:
        source.close()

    return {
        "status": "migrated",
        "migration_id": SQLITE_CUTOVER_MIGRATION_ID,
        "source_path": str(source_path),
        "source_fingerprint": source_fingerprint,
        "table_count": len(tables),
        "row_count": sum(source_counts.values()),
        "table_counts": source_counts,
        "applied_at": applied_at,
    }


def _source_tables(connection: sqlite3.Connection) -> list[str]:
    rows = connection.execute(
        """
        select name from sqlite_master
        where type = 'table' and name not like 'sqlite_%'
        order by name
        """
    ).fetchall()
    tables = [str(row["name"]) for row in rows]
    for table in tables:
        _validate_identifier(table)
    return tables


def _ensure_source_schema(source: sqlite3.Connection, target: PostgresConnection) -> None:
    objects = source.execute(
        """
        select type, name, sql from sqlite_master
        where type in ('table', 'index')
          and sql is not null
          and name not like 'sqlite_%'
        """
    ).fetchall()
    tables = {str(row["name"]): row for row in objects if str(row["type"]) == "table"}
    indexes = sorted(
        (row for row in objects if str(row["type"]) == "index"),
        key=lambda row: str(row["name"]),
    )
    for table in _foreign_key_order(source, tables):
        row = tables[table]
        target.execute(translate_schema_sql(str(row["sql"]), "table"))
    for row in indexes:
        object_type = str(row["type"])
        if object_type != "index":
            raise RuntimeError(f"不支持的 SQLite schema 对象：{object_type}")
        target.execute(translate_schema_sql(str(row["sql"]), object_type))


def _foreign_key_order(
    source: sqlite3.Connection,
    tables: dict[str, sqlite3.Row],
) -> tuple[str, ...]:
    dependencies = {
        table: {
            str(row["table"])
            for row in source.execute(f"pragma foreign_key_list({table})").fetchall()
            if str(row["table"]) in tables and str(row["table"]) != table
        }
        for table in tables
    }
    ordered: list[str] = []
    remaining = set(tables)
    while remaining:
        ready = sorted(table for table in remaining if dependencies[table].isdisjoint(remaining))
        if not ready:
            cycle = {table: sorted(dependencies[table] & remaining) for table in sorted(remaining)}
            raise RuntimeError(f"SQLite schema 存在循环外键依赖：{json.dumps(cycle, ensure_ascii=False)}")
        ordered.extend(ready)
        remaining.difference_update(ready)
    return tuple(ordered)


def _require_empty_target(connection: PostgresConnection, source_counts: dict[str, int]) -> None:
    non_empty: dict[str, int] = {}
    for table in source_counts:
        row_count = _target_count(connection, table)
        if row_count > 0:
            non_empty[table] = row_count
    if non_empty:
        raise RuntimeError(
            "PostgreSQL 目标库不是空库，拒绝覆盖："
            + json.dumps(non_empty, ensure_ascii=False, sort_keys=True)
        )


def _copy_table(
    source: sqlite3.Connection,
    target: PostgresConnection,
    table: str,
    batch_size: int,
) -> int:
    _validate_identifier(table)
    columns = [str(row["name"]) for row in source.execute(f"pragma table_info({table})").fetchall()]
    if not columns:
        raise RuntimeError(f"SQLite 表缺少字段：{table}")
    for column in columns:
        _validate_identifier(column)
    placeholders = ", ".join("?" for _ in columns)
    statement = f"insert into {table} ({', '.join(columns)}) values ({placeholders})"
    cursor = source.execute(f"select {', '.join(columns)} from {table}")
    copied = 0
    while True:
        rows = cursor.fetchmany(batch_size)
        if not rows:
            break
        target.executemany(statement, [tuple(row) for row in rows])
        copied += len(rows)
    return copied


def _reset_sequences(connection: PostgresConnection, tables: list[str]) -> None:
    if "fetch_runs" not in tables:
        return
    connection.execute(
        """
        select setval(
          pg_get_serial_sequence('fetch_runs', 'run_id'),
          coalesce((select max(run_id) from fetch_runs), 1),
          exists(select 1 from fetch_runs)
        )
        """
    )


def _source_count(connection: sqlite3.Connection, table: str) -> int:
    _validate_identifier(table)
    return int(connection.execute(f"select count(*) from {table}").fetchone()[0])


def _target_count(connection: PostgresConnection, table: str) -> int:
    _validate_identifier(table)
    return int(connection.execute(f"select count(*) from {table}").fetchone()[0])


def _validate_identifier(value: str) -> None:
    if not _IDENTIFIER.fullmatch(value):
        raise ValueError(f"非法数据库标识符：{value}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
