from __future__ import annotations

import unittest

from finbot.storage.postgres_store import split_sql_script, sqlite_schema_metadata, translate_schema_sql, translate_sql


class PostgresCompatibilityTests(unittest.TestCase):
    def test_qmark_translation_ignores_quoted_question_marks(self) -> None:
        translated = translate_sql("select '?' as literal, value from sample where id = ? and note = '??'")

        self.assertEqual(
            translated,
            "select '?' as literal, value from sample where id = %s and note = '??'",
        )

    def test_insert_or_ignore_maps_to_postgres_conflict(self) -> None:
        translated = translate_sql("insert or ignore into sample (id, value) values (?, ?)")

        self.assertEqual(
            translated,
            "insert into sample (id, value) values (%s, %s) on conflict do nothing",
        )

    def test_insert_or_replace_uses_composite_primary_key(self) -> None:
        translated = translate_sql(
            "insert or replace into instrument_aliases (alias_key, provider, market_type, instrument_id, symbol) values (?, ?, ?, ?, ?)",
            {"instrument_aliases": ("alias_key", "provider", "market_type", "instrument_id")},
        )

        self.assertIn(
            "on conflict (alias_key, provider, market_type, instrument_id) do update set symbol = excluded.symbol",
            translated,
        )
        self.assertEqual(translated.count("%s"), 5)

    def test_schema_translation_removes_sqlite_only_tokens(self) -> None:
        objects, primary_keys = sqlite_schema_metadata()
        translated = [translate_schema_sql(statement, object_type) for object_type, _name, statement in objects]

        self.assertGreater(len(translated), 50)
        self.assertEqual(primary_keys["instrument_aliases"], ("alias_key", "provider", "market_type", "instrument_id"))
        combined = "\n".join(translated).lower()
        self.assertNotIn("autoincrement", combined)
        self.assertNotIn("strftime(", combined)
        self.assertIn("bigserial primary key", combined)
        self.assertIn("double precision", combined)

    def test_begin_immediate_uses_transaction_advisory_lock(self) -> None:
        self.assertEqual(
            translate_sql("begin immediate"),
            "select pg_advisory_xact_lock(hashtext('finbot-critical-write'))",
        )

    def test_runtime_schema_ddl_uses_postgres_types(self) -> None:
        translated = translate_sql(
            "create table sample (id integer primary key autoincrement, score real not null)"
        )

        self.assertIn("create table if not exists sample", translated.lower())
        self.assertIn("bigserial primary key", translated.lower())
        self.assertIn("double precision", translated.lower())

    def test_sql_script_splitter_preserves_semicolons_inside_literals(self) -> None:
        statements = split_sql_script(
            "create table sample (id text); insert into sample (id) values ('a;b');"
        )

        self.assertEqual(len(statements), 2)
        self.assertIn("'a;b'", statements[1])


if __name__ == "__main__":
    unittest.main()
