from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field


class OfficialRelease(BaseModel):
    release_id: str
    provider: str
    release_type: str
    title: str
    scheduled_at: datetime
    timezone: str | None = None
    asset_scope: list[str] = Field(default_factory=list)
    expected_fields: list[str] = Field(default_factory=list)
    source_ids: list[str] = Field(default_factory=list)
    source_url: str | None = None
    status: str = "scheduled"
    match_terms: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class OfficialReleaseRegistry:
    def __init__(self, releases: list[OfficialRelease]):
        self.releases = releases

    @classmethod
    def load(cls, path: str | Path) -> "OfficialReleaseRegistry":
        p = Path(path)
        data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        return cls([OfficialRelease(**item) for item in data.get("releases", [])])
