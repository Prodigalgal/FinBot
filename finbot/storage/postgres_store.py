from __future__ import annotations

import re
import sqlite3
import tempfile
import threading
from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path
from typing import Any

import psycopg
from psycopg_pool import ConnectionPool

from finbot.storage.errors import StoreIntegrityError
from finbot.storage.sqlite_store import SQLiteStore


_POSTGRES_SCHEMA_LOCK = threading.Lock()
_POSTGRES_SCHEMA_READY: set[str] = set()
_IDENTIFIER = re.compile(r"^[a-z_][a-z0-9_]*$")
_INSERT_REPLACE = re.compile(
    r"^\s*insert\s+or\s+replace\s+into\s+([a-z_][a-z0-9_]*)\s*"
    r"\((.*?)\)\s*values\s*\((.*?)\)\s*;?\s*$",
    re.IGNORECASE | re.DOTALL,
)


class HybridRow(Mapping[str, Any]):
    def __init__(self, columns: Sequence[str], values: Sequence[Any]):
        self._columns = tuple(columns)
        self._values = tuple(values)
        self._mapping = dict(zip(self._columns, self._values, strict=True))

    def __getitem__(self, key: str | int | slice) -> Any:
        if isinstance(key, (int, slice)):
            return self._values[key]
        return self._mapping[key]

    def __iter__(self) -> Iterator[str]:
        return iter(self._columns)

    def __len__(self) -> int:
        return len(self._columns)

    def keys(self) -> tuple[str, ...]:
        return self._columns


def hybrid_row_factory(cursor: psycopg.Cursor[Any]) -> Any:
    columns = tuple(column.name for column in cursor.description or ())
    return lambda values: HybridRow(columns, values)


class PostgresConnection:
    def __init__(self, connection: psycopg.Connection[Any], primary_keys: Mapping[str, tuple[str, ...]]):
        self._connection = connection
        self._primary_keys = primary_keys

    def execute(self, statement: str, params: Sequence[Any] | None = None) -> psycopg.Cursor[Any]:
        translated = translate_sql(statement, self._primary_keys)
        try:
            return self._connection.execute(translated, tuple(params or ()))
        except psycopg.IntegrityError as exc:
            raise StoreIntegrityError(str(exc)) from exc

    def executemany(self, statement: str, params_seq: Sequence[Sequence[Any]]) -> psycopg.Cursor[Any]:
        translated = translate_sql(statement, self._primary_keys)
        cursor = self._connection.cursor()
        try:
            cursor.executemany(translated, params_seq)
        except psycopg.IntegrityError as exc:
            raise StoreIntegrityError(str(exc)) from exc
        return cursor

    def executescript(self, script: str) -> psycopg.Cursor[Any] | None:
        cursor: psycopg.Cursor[Any] | None = None
        for statement in split_sql_script(script):
            cursor = self.execute(statement)
        return cursor


class PostgresStore(SQLiteStore):
    """PostgreSQL production store preserving the established Store method contract."""

    backend = "postgresql"

    def __init__(self, database_url: str, *, min_pool_size: int = 1, max_pool_size: int = 10):
        if not database_url.strip():
            raise ValueError("FINBOT_DATABASE_URL 不能为空")
        self.database_url = database_url.strip()
        self.path = Path("postgresql")
        self._primary_keys = sqlite_schema_metadata()[1]
        self._pool = ConnectionPool(
            conninfo=self.database_url,
            min_size=max(1, min_pool_size),
            max_size=max(min_pool_size, max_pool_size),
            kwargs={"row_factory": hybrid_row_factory},
            open=False,
        )
        self._pool.open(wait=True, timeout=30.0)

    @contextmanager
    def connect(self) -> Iterator[PostgresConnection]:
        with self._pool.connection() as connection:
            with connection.transaction():
                yield PostgresConnection(connection, self._primary_keys)

    def close(self) -> None:
        self._pool.close()

    def init_schema(self) -> None:
        schema_key = _redacted_schema_key(self.database_url)
        if schema_key in _POSTGRES_SCHEMA_READY:
            return
        with _POSTGRES_SCHEMA_LOCK:
            if schema_key in _POSTGRES_SCHEMA_READY:
                return
            schema_objects, _primary_keys = sqlite_schema_metadata()
            with self._pool.connection() as connection:
                with connection.transaction():
                    for object_type, _name, statement in schema_objects:
                        translated = translate_schema_sql(statement, object_type)
                        connection.execute(translated)
                    connection.execute(
                        """
                        create table if not exists finbot_schema_migrations (
                          migration_id text primary key,
                          source_kind text not null,
                          source_fingerprint text not null,
                          table_counts_json text not null,
                          applied_at text not null
                        )
                        """
                    )
            _POSTGRES_SCHEMA_READY.add(schema_key)

    def table_exists(self, table: str) -> bool:
        _validate_identifier(table)
        with self.connect() as connection:
            row = connection.execute(
                "select 1 from information_schema.tables where table_schema = current_schema() and table_name = ?",
                (table,),
            ).fetchone()
        return row is not None


def translate_sql(statement: str, primary_keys: Mapping[str, tuple[str, ...]] | None = None) -> str:
    normalized = statement.strip()
    if normalized.lower() == "begin immediate":
        return "select pg_advisory_xact_lock(hashtext('finbot-critical-write'))"
    if re.match(r"^create\s+table\b", normalized, re.IGNORECASE):
        return translate_schema_sql(statement, "table")
    if re.match(r"^create\s+(?:unique\s+)?index\b", normalized, re.IGNORECASE):
        return translate_schema_sql(statement, "index")

    match = _INSERT_REPLACE.match(statement)
    if match:
        table = match.group(1).lower()
        columns = tuple(part.strip() for part in match.group(2).split(","))
        values = match.group(3).strip()
        keys = tuple((primary_keys or {}).get(table, ()))
        if not keys:
            raise ValueError(f"PostgreSQL upsert 缺少主键元数据：{table}")
        updates = [column for column in columns if column not in keys]
        conflict = ", ".join(keys)
        action = (
            "do update set " + ", ".join(f"{column} = excluded.{column}" for column in updates)
            if updates
            else "do nothing"
        )
        statement = (
            f"insert into {table} ({', '.join(columns)}) values ({values}) "
            f"on conflict ({conflict}) {action}"
        )
    elif re.match(r"^\s*insert\s+or\s+ignore\s+into\s+", statement, re.IGNORECASE):
        statement = re.sub(
            r"^\s*insert\s+or\s+ignore\s+into\s+",
            "insert into ",
            statement,
            count=1,
            flags=re.IGNORECASE,
        ).rstrip().rstrip(";") + " on conflict do nothing"

    return _qmark_to_psycopg(statement)


def split_sql_script(script: str) -> tuple[str, ...]:
    statements: list[str] = []
    current: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(script):
        character = script[index]
        if quote:
            current.append(character)
            if character == quote:
                if index + 1 < len(script) and script[index + 1] == quote:
                    current.append(script[index + 1])
                    index += 1
                else:
                    quote = None
        elif character in {"'", '"'}:
            quote = character
            current.append(character)
        elif character == ";":
            statement = "".join(current).strip()
            if statement:
                statements.append(statement)
            current = []
        else:
            current.append(character)
        index += 1
    trailing = "".join(current).strip()
    if trailing:
        statements.append(trailing)
    return tuple(statements)


def translate_schema_sql(statement: str, object_type: str) -> str:
    translated = statement.strip().rstrip(";")
    if object_type == "table":
        translated = re.sub(
            r"^create\s+table\s+(?:if\s+not\s+exists\s+)?",
            "create table if not exists ",
            translated,
            flags=re.IGNORECASE,
        )
        translated = re.sub(
            r"integer\s+primary\s+key\s+autoincrement",
            "bigserial primary key",
            translated,
            flags=re.IGNORECASE,
        )
        translated = re.sub(r"\breal\b", "double precision", translated, flags=re.IGNORECASE)
        translated = translated.replace(
            "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')",
            "to_char(current_timestamp at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')",
        )
    elif object_type == "index":
        translated = re.sub(
            r"^create\s+(unique\s+)?index\s+(?:if\s+not\s+exists\s+)?",
            lambda match: f"create {match.group(1) or ''}index if not exists ",
            translated,
            flags=re.IGNORECASE,
        )
    return translated


@lru_cache(maxsize=1)
def sqlite_schema_metadata() -> tuple[tuple[tuple[str, str, str], ...], dict[str, tuple[str, ...]]]:
    with tempfile.TemporaryDirectory(prefix="finbot-schema-") as temp_dir:
        store = SQLiteStore(Path(temp_dir) / "schema.sqlite3")
        store.init_schema()
        with store.connect() as connection:
            rows = connection.execute(
                """
                select type, name, sql
                from sqlite_master
                where type in ('table', 'index')
                  and sql is not null
                  and name not like 'sqlite_%'
                order by case type when 'table' then 0 else 1 end, name
                """
            ).fetchall()
            tables = [row["name"] for row in rows if row["type"] == "table"]
            primary_keys = {
                table: tuple(
                    row["name"]
                    for row in sorted(
                        connection.execute(f"pragma table_info({table})").fetchall(),
                        key=lambda item: int(item["pk"] or 0),
                    )
                    if int(row["pk"] or 0) > 0
                )
                for table in tables
            }
        objects = tuple((str(row["type"]), str(row["name"]), str(row["sql"])) for row in rows)
    return objects, primary_keys


def _qmark_to_psycopg(statement: str) -> str:
    output: list[str] = []
    quote: str | None = None
    index = 0
    while index < len(statement):
        character = statement[index]
        if quote:
            output.append(character)
            if character == quote:
                if index + 1 < len(statement) and statement[index + 1] == quote:
                    output.append(statement[index + 1])
                    index += 1
                else:
                    quote = None
        elif character in {"'", '"'}:
            quote = character
            output.append(character)
        elif character == "?":
            output.append("%s")
        else:
            output.append(character)
        index += 1
    return "".join(output)


def _redacted_schema_key(database_url: str) -> str:
    parsed = psycopg.conninfo.conninfo_to_dict(database_url)
    return f"{parsed.get('host')}:{parsed.get('port')}:{parsed.get('dbname')}:{parsed.get('user')}"


def _validate_identifier(value: str) -> None:
    if not _IDENTIFIER.fullmatch(value):
        raise ValueError(f"非法数据库标识符：{value}")
