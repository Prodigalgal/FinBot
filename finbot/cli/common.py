from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from finbot.config.settings import Settings
from finbot.storage.sqlite_store import SQLiteStore


def build_store(data_dir: str = "data") -> tuple[Settings, SQLiteStore]:
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(data_dir))
    settings.ensure_dirs()
    store = SQLiteStore(settings.sqlite_path)
    store.init_schema()
    return settings, store


def write_report(settings: Settings, filename: str, payload: dict[str, Any]) -> Path:
    output = settings.reports_dir / filename
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    return output
