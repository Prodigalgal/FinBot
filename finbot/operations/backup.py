from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import tarfile
import tempfile
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


CONFIG_FILES = ("runtime_config.json", "proxy_policy.json", "ai_sites.json")


def create_runtime_backup(runtime_root: Path, output: Path | None = None) -> dict[str, Any]:
    root = runtime_root.resolve()
    source_db = root / "data" / "finbot.sqlite3"
    if not source_db.is_file():
        raise FileNotFoundError(f"SQLite database not found: {source_db}")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    archive = (output or root / "backups" / f"finbot-{stamp}.tar.gz").resolve()
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="finbot-backup-") as temp_dir:
        bundle_root = Path(temp_dir) / stamp
        bundle_root.mkdir()
        backup_db = bundle_root / "finbot.sqlite3"
        with closing(sqlite3.connect(source_db)) as source, closing(sqlite3.connect(backup_db)) as target:
            source.backup(target)
            target.commit()
        for name in CONFIG_FILES:
            source = root / "config" / name
            if source.is_file():
                shutil.copy2(source, bundle_root / name)
        manifest = {
            "format_version": 1,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "source_database": str(source_db),
            "files": _file_manifest(bundle_root),
        }
        (bundle_root / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=True, indent=2),
            encoding="utf-8",
        )
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(bundle_root, arcname=stamp)
    verified = verify_runtime_backup(archive)
    return {"status": "created", "archive": str(archive), **verified}


def verify_runtime_backup(archive: Path) -> dict[str, Any]:
    source = archive.resolve()
    if not source.is_file():
        raise FileNotFoundError(source)
    with tempfile.TemporaryDirectory(prefix="finbot-verify-") as temp_dir:
        target = Path(temp_dir).resolve()
        with tarfile.open(source, "r:gz") as tar:
            members = tar.getmembers()
            for member in members:
                destination = (target / member.name).resolve()
                if target not in destination.parents and destination != target:
                    raise ValueError("backup archive contains unsafe path")
            tar.extractall(target, members=members, filter="data")
        roots = [path for path in target.iterdir() if path.is_dir()]
        if len(roots) != 1:
            raise ValueError("backup archive must contain exactly one root directory")
        bundle_root = roots[0]
        manifest = json.loads((bundle_root / "manifest.json").read_text(encoding="utf-8"))
        for name, expected in manifest["files"].items():
            path = bundle_root / name
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected["sha256"]:
                raise ValueError(f"backup checksum mismatch: {name}")
        with closing(sqlite3.connect(bundle_root / "finbot.sqlite3")) as connection:
            integrity = connection.execute("pragma integrity_check").fetchone()[0]
            table_count = int(connection.execute("select count(*) from sqlite_master where type='table'").fetchone()[0])
        if integrity != "ok":
            raise ValueError(f"SQLite integrity check failed: {integrity}")
    return {
        "verification": "passed",
        "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "bytes": source.stat().st_size,
        "table_count": table_count,
    }


def restore_runtime_backup(archive: Path, runtime_root: Path, *, overwrite: bool = False) -> dict[str, Any]:
    verification = verify_runtime_backup(archive)
    root = runtime_root.resolve()
    target_db = root / "data" / "finbot.sqlite3"
    if target_db.exists() and not overwrite:
        raise FileExistsError("target database exists; pass overwrite=True only during a controlled restore")
    with tempfile.TemporaryDirectory(prefix="finbot-restore-") as temp_dir:
        temp_root = Path(temp_dir)
        with tarfile.open(archive.resolve(), "r:gz") as tar:
            tar.extractall(temp_root, filter="data")
        bundle_root = next(path for path in temp_root.iterdir() if path.is_dir())
        target_db.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(bundle_root / "finbot.sqlite3", target_db)
        config_dir = root / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        for name in CONFIG_FILES:
            source = bundle_root / name
            if source.is_file():
                shutil.copy2(source, config_dir / name)
    return {"status": "restored", "runtime_root": str(root), **verification}


def _file_manifest(directory: Path) -> dict[str, dict[str, Any]]:
    return {
        path.name: {
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        for path in sorted(directory.iterdir())
        if path.is_file()
    }
