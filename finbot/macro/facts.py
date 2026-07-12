from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from finbot.storage.sqlite_store import SQLiteStore


@dataclass(frozen=True)
class MacroReleaseFact:
    fact_id: str
    source_id: str
    evidence_id: str
    provider: str
    release_type: str
    observed_at: str
    fields: dict[str, Any]
    asset_scope: list[str]
    confidence: float
    notes: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)


class MacroFactBuilder:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def build(self) -> dict[str, Any]:
        self.store.init_schema()
        self.store.clear_macro_release_facts()
        source_map = self.store.source_map()
        facts: list[MacroReleaseFact] = []
        for row in self.store.list_raw_evidence(only_success=True):
            source_id = row["source_id"]
            if source_id == "official_bls_api":
                facts.extend(self._bls_facts(row, source_map.get(source_id)))
            elif source_id == "official_eia_weekly_petroleum":
                fact = self._eia_release_schedule_fact(row, source_map.get(source_id))
                if fact:
                    facts.append(fact)

        for fact in facts:
            self.store.insert_macro_release_fact(fact)
        return {
            "facts_created": len(facts),
            "providers": self._count_by(facts, "provider"),
            "release_types": self._count_by(facts, "release_type"),
            "facts": [fact.__dict__ for fact in facts],
        }

    def _bls_facts(self, row, source) -> list[MacroReleaseFact]:
        payload = _load_json(row["response_path"])
        series = (((payload or {}).get("Results") or {}).get("series") or [])
        facts: list[MacroReleaseFact] = []
        for item in series:
            data_points = item.get("data") or []
            if not data_points:
                continue
            latest = data_points[0]
            fields = {
                "series_id": item.get("seriesID"),
                "period": latest.get("period"),
                "period_name": latest.get("periodName"),
                "year": latest.get("year"),
                "value": _to_float(latest.get("value")),
                "latest": str(latest.get("latest", "")).lower() == "true",
            }
            facts.append(
                MacroReleaseFact(
                    fact_id=uuid4().hex,
                    source_id=row["source_id"],
                    evidence_id=row["evidence_id"],
                    provider="bls",
                    release_type=self._bls_release_type(item.get("seriesID")),
                    observed_at=row["fetched_at"],
                    fields=fields,
                    asset_scope=_source_assets(source),
                    confidence=0.82,
                    notes=["BLS public API latest data point; consensus/surprise not available yet."],
                    metadata={"status": payload.get("status"), "response_time_ms": payload.get("responseTime")},
                )
            )
        return facts

    def _eia_release_schedule_fact(self, row, source) -> MacroReleaseFact | None:
        markdown_path = row["markdown_path"]
        if not markdown_path or not Path(markdown_path).exists():
            return None
        text = Path(markdown_path).read_text(encoding="utf-8", errors="ignore")
        week_ending = _match_date(r"Data for week ending\s+([A-Za-z]+\s+\d{1,2},\s+\d{4})", text)
        release_date = _match_date(r"Release Date:\s*([A-Za-z]+\s+\d{1,2},\s+\d{4})", text)
        next_release_date = _match_date(r"Next Release Date:\s*([A-Za-z]+\s+\d{1,2},\s+\d{4})", text)
        if not any([week_ending, release_date, next_release_date]):
            return None
        return MacroReleaseFact(
            fact_id=uuid4().hex,
            source_id=row["source_id"],
            evidence_id=row["evidence_id"],
            provider="eia",
            release_type="weekly_petroleum_status_schedule",
            observed_at=row["fetched_at"],
            fields={
                "week_ending": week_ending,
                "release_date": release_date,
                "next_release_date": next_release_date,
            },
            asset_scope=_source_assets(source),
            confidence=0.76,
            notes=["EIA page schedule extracted from Firecrawl markdown; inventory values require PDF/table extraction."],
            metadata={"markdown_path": markdown_path},
        )

    def _bls_release_type(self, series_id: str | None) -> str:
        if series_id == "CUUR0000SA0":
            return "cpi_index_level"
        return "bls_series_latest"

    def _count_by(self, facts: list[MacroReleaseFact], attr: str) -> dict[str, int]:
        counts: dict[str, int] = {}
        for fact in facts:
            key = str(getattr(fact, attr))
            counts[key] = counts.get(key, 0) + 1
        return dict(sorted(counts.items()))


def _load_json(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    try:
        return json.loads(Path(path).read_text(encoding="utf-8", errors="ignore"))
    except Exception:
        return {}


def _to_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _source_assets(source) -> list[str]:
    if not source:
        return []
    try:
        return json.loads(source["asset_scope_json"])
    except Exception:
        return []


def _match_date(pattern: str, text: str) -> str | None:
    match = re.search(pattern, text, flags=re.IGNORECASE)
    if not match:
        return None
    value = match.group(1).strip()
    try:
        parsed = datetime.strptime(value, "%B %d, %Y").replace(tzinfo=timezone.utc)
        return parsed.date().isoformat()
    except ValueError:
        return value
