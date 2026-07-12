from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class EvidenceStore:
    def __init__(self, base_dir: Path):
        self.base_dir = base_dir
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def _dir_for(self, source_id: str) -> Path:
        now = datetime.now(timezone.utc)
        target = self.base_dir / f"{now:%Y}" / f"{now:%m}" / f"{now:%d}" / source_id
        target.mkdir(parents=True, exist_ok=True)
        return target

    def save_json(self, source_id: str, name: str, payload: Any) -> str:
        path = self._dir_for(source_id) / f"{name}.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        return str(path)

    def save_text(self, source_id: str, name: str, text: str, suffix: str = ".txt") -> str:
        path = self._dir_for(source_id) / f"{name}{suffix}"
        path.write_text(text or "", encoding="utf-8")
        return str(path)

