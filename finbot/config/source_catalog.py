from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

from finbot.ingestion.models import SourceConfig


class SourceCatalog:
    def __init__(self, sources: list[SourceConfig], global_limits: dict[str, Any] | None = None):
        self.sources = sources
        self.global_limits = global_limits or {}

    @classmethod
    def load(cls, path: str | Path) -> "SourceCatalog":
        p = Path(path)
        data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        sources = [SourceConfig(**item) for item in data.get("sources", [])]
        return cls(sources=sources, global_limits=data.get("global_limits") or {})

    def by_id(self, source_id: str) -> SourceConfig:
        for source in self.sources:
            if source.id == source_id:
                return source
        raise KeyError(source_id)

