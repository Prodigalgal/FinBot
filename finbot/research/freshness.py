from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any


DEFAULT_MAX_AGE_HOURS = {
    "social": 6,
    "social_fetch": 6,
    "market_data": 24,
    "broad_market_news": 72,
    "crypto_news": 72,
    "energy_news": 72,
    "macro_market_news": 72,
    "global_news_discovery": 72,
    "macro_data": 168,
    "official": 168,
    "policy": 168,
    "filing": 720,
}

BACKGROUND_GRACE_MULTIPLIER = 4
DURABLE_CATEGORIES = {"macro_data", "official", "policy", "filing"}


@dataclass(frozen=True)
class FreshnessDecision:
    status: str
    age_hours: float
    max_age_hours: int
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "age_hours": round(self.age_hours, 2),
            "max_age_hours": self.max_age_hours,
            "reason": self.reason,
        }


class FreshnessGate:
    def __init__(self, max_age_hours: dict[str, int] | None = None, now: datetime | None = None):
        self.max_age_hours = {**DEFAULT_MAX_AGE_HOURS, **(max_age_hours or {})}
        self.now = now or datetime.now(timezone.utc)

    def classify_document(self, row: Any) -> FreshnessDecision:
        category = str(_row_get(row, "category") or "unknown")
        tier = str(_row_get(row, "tier") or "")
        fetched_at = _parse_dt(_row_get(row, "published_at") or _row_get(row, "fetched_at"))
        age_hours = max(0.0, (self.now - fetched_at).total_seconds() / 3600)
        max_age = self._max_age_for(category, tier)

        if age_hours <= max_age:
            return FreshnessDecision("fresh", age_hours, max_age, "within_primary_event_window")

        if category in DURABLE_CATEGORIES or tier == "T1":
            return FreshnessDecision("stale-context", age_hours, max_age, "durable_or_official_context")

        if age_hours <= max_age * BACKGROUND_GRACE_MULTIPLIER:
            return FreshnessDecision("stale-context", age_hours, max_age, "outside_primary_window_but_recent_enough_for_background")

        return FreshnessDecision("discarded-stale", age_hours, max_age, "too_old_for_current_market_impact")

    def policy(self) -> dict[str, Any]:
        return {
            "policy": "freshness-first-no-heavy-rag",
            "default_max_age_hours": self.max_age_hours,
            "background_grace_multiplier": BACKGROUND_GRACE_MULTIPLIER,
            "durable_categories": sorted(DURABLE_CATEGORIES),
            "rules": [
                "Fresh evidence can enter the main research card.",
                "Stale context is background only and cannot drive the event conclusion.",
                "Discarded stale items are retained as references but excluded from main evidence.",
                "Official and durable macro/filing context may survive longer than ordinary news.",
            ],
        }

    def _max_age_for(self, category: str, tier: str) -> int:
        if category in self.max_age_hours:
            return self.max_age_hours[category]
        if tier == "T1":
            return self.max_age_hours["official"]
        return 72


def document_ref(row: Any, freshness: FreshnessDecision | None = None) -> dict[str, Any]:
    value = {
        "document_id": _row_get(row, "document_id"),
        "evidence_id": _row_get(row, "evidence_id"),
        "source_id": _row_get(row, "source_id"),
        "tier": _row_get(row, "tier"),
        "category": _row_get(row, "category"),
        "trust_weight": _row_get(row, "trust_weight"),
        "canonical_url": _row_get(row, "canonical_url"),
        "title": _row_get(row, "title"),
        "published_at": _row_get(row, "published_at"),
        "fetched_at": _row_get(row, "fetched_at"),
        "asset_scope": _loads(_row_get(row, "asset_scope_json"), []),
        "text_preview": _preview(_row_get(row, "text") or ""),
    }
    if freshness is not None:
        value["freshness"] = freshness.to_dict()
    return value


def _preview(text: str) -> str:
    return " ".join(text.split())[:600]


def _row_get(row: Any, key: str) -> Any:
    try:
        return row[key]
    except Exception:
        if isinstance(row, dict):
            return row.get(key)
        return getattr(row, key, None)


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _parse_dt(value: str | None) -> datetime:
    if not value:
        return datetime.now(timezone.utc)
    try:
        parsed = datetime.fromisoformat(value)
    except Exception:
        return datetime.now(timezone.utc)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed
