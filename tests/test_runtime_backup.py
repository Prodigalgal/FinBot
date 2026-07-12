from __future__ import annotations

import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from finbot.operations.backup import create_runtime_backup, restore_runtime_backup, verify_runtime_backup


class RuntimeBackupTests(unittest.TestCase):
    def test_backup_verify_and_restore_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "source"
            (source / "data").mkdir(parents=True)
            (source / "config").mkdir()
            with closing(sqlite3.connect(source / "data" / "finbot.sqlite3")) as connection:
                connection.execute("create table sample (id integer primary key, value text)")
                connection.execute("insert into sample(value) values ('ready')")
                connection.commit()
            (source / "config" / "runtime_config.json").write_text("{}", encoding="utf-8")
            archive = Path(temp_dir) / "backup.tar.gz"
            created = create_runtime_backup(source, archive)
            verified = verify_runtime_backup(archive)
            restored_root = Path(temp_dir) / "restored"
            restored = restore_runtime_backup(archive, restored_root)
            with closing(sqlite3.connect(restored_root / "data" / "finbot.sqlite3")) as connection:
                value = connection.execute("select value from sample").fetchone()[0]

        self.assertEqual(created["verification"], "passed")
        self.assertEqual(verified["table_count"], 1)
        self.assertEqual(restored["status"], "restored")
        self.assertEqual(value, "ready")

    def test_restore_refuses_to_overwrite_existing_database(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "source"
            (root / "data").mkdir(parents=True)
            sqlite3.connect(root / "data" / "finbot.sqlite3").close()
            archive = Path(temp_dir) / "backup.tar.gz"
            create_runtime_backup(root, archive)
            with self.assertRaises(FileExistsError):
                restore_runtime_backup(archive, root)


if __name__ == "__main__":
    unittest.main()
