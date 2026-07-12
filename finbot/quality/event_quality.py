from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timezone
from statistics import mean
from typing import Any, Iterable, Sequence


POSITIVE_TERMS = {
    "approval",
    "approved",
    "beat",
    "bullish",
    "cut",
    "draw",
    "easing",
    "growth",
    "jump",
    "rally",
    "shortage",
    "surge",
}

NEGATIVE_TERMS = {
    "bearish",
    "build",
    "ceasefire",
    "delay",
    "drop",
    "fall",
    "miss",
    "recession",
    "rejection",
    "slowdown",
    "slump",
    "selloff",
}

GENERIC_TITLE_KEYS = {
    "announcement",
    "announcements",
    "coin prices",
    "commodities market",
    "doctype html",
    "first mover",
    "in focus",
    "news",
    "recent actions office of foreign assets control",
    "releases",
    "rss feeds",
    "status requestsucceeded",
    "status request_succeeded",
}
GENERIC_TITLE_PATTERNS = (
    "all releases",
    "european central bank",
    "skip navigation",
    "skip to main content",
)
TOPIC_TERMS = {
    "apple",
    "bitcoin",
    "brent",
    "cpi",
    "crude",
    "crypto",
    "dollar",
    "earnings",
    "eia",
    "etf",
    "fed",
    "fomc",
    "gold",
    "inflation",
    "inventory",
    "nasdaq",
    "nvidia",
    "opec",
    "oil",
    "payrolls",
    "pce",
    "rates",
    "sanctions",
    "stocks",
    "tariff",
    "treasury",
    "wti",
    "yields",
}
TOPIC_SENSITIVE_CATEGORIES = {
    "broad_market_news",
    "global_news_discovery",
    "macro_market_news",
    "energy_news",
    "crypto_news",
}


class EventQualityEvaluator:
    def __init__(self, market_rows: Sequence[Any] | None = None):
        self.market_rows = list(market_rows or [])

    def evaluate(self, docs: Sequence[Any]) -> dict[str, Any]:
        if not docs:
            return self._empty()

        source_ids = sorted({_row_get(row, "source_id") for row in docs if _row_get(row, "source_id")})
        categories = Counter(_row_get(row, "category") or "unknown" for row in docs)
        tiers = Counter(_row_get(row, "tier") or "unknown" for row in docs)
        assets = sorted({asset for row in docs for asset in _loads(_row_get(row, "asset_scope_json"), [])})
        trust_values = [float(_row_get(row, "trust_weight") or 0.0) for row in docs]
        avg_trust = mean(trust_values) if trust_values else 0.0
        last_seen = _parse_dt(max((_row_get(row, "fetched_at") or "") for row in docs))
        first_seen = _parse_dt(min((_row_get(row, "fetched_at") or "") for row in docs))
        conflicts = self._conflicts(docs)
        market_confirmation = self._market_confirmation(assets, last_seen)
        generic_landing_page = self._generic_landing_page(docs)
        weak_topic_match = self._weak_topic_match(docs)

        review_flags = self._review_flags(
            docs=docs,
            source_ids=source_ids,
            tiers=tiers,
            categories=categories,
            avg_trust=avg_trust,
            market_confirmation=market_confirmation,
            conflicts=conflicts,
            generic_landing_page=generic_landing_page,
            weak_topic_match=weak_topic_match,
        )
        quality_score = self._quality_score(
            docs=docs,
            source_ids=source_ids,
            tiers=tiers,
            avg_trust=avg_trust,
            market_confirmation=market_confirmation,
            conflicts=conflicts,
            last_seen=last_seen,
            generic_landing_page=generic_landing_page,
            weak_topic_match=weak_topic_match,
        )
        confirmation_state = self._confirmation_state(quality_score, tiers, len(source_ids), market_confirmation)
        priority = self._priority(quality_score, confirmation_state, categories)

        return {
            "quality_score": quality_score,
            "confirmation_state": confirmation_state,
            "priority": priority,
            "evidence_tiers": dict(tiers),
            "source_categories": dict(categories),
            "average_trust_weight": round(avg_trust, 3),
            "first_seen_at": first_seen.isoformat(),
            "last_seen_at": last_seen.isoformat(),
            "market_confirmation": market_confirmation,
            "conflict_flags": conflicts,
            "review_flags": review_flags,
            "suggested_followups": self._followups(review_flags, conflicts),
        }

    def _empty(self) -> dict[str, Any]:
        return {
            "quality_score": 0.0,
            "confirmation_state": "insufficient-evidence",
            "priority": "P3",
            "evidence_tiers": {},
            "source_categories": {},
            "average_trust_weight": 0.0,
            "market_confirmation": {"status": "not-applicable", "reason": "no documents"},
            "conflict_flags": [],
            "review_flags": ["empty_event_cluster"],
            "suggested_followups": ["Collect evidence before sending this event to AI research."],
        }

    def _market_confirmation(self, assets: list[str], event_last_seen: datetime) -> dict[str, Any]:
        if not assets:
            return {"status": "not-applicable", "reason": "event has no mapped assets"}

        matches: list[Any] = []
        asset_set = set(assets)
        for row in self.market_rows:
            row_assets = set(_loads(_row_get(row, "asset_scope_json"), []))
            if asset_set.intersection(row_assets):
                matches.append(row)

        if not matches:
            return {
                "status": "missing",
                "asset_scope": assets,
                "reason": "no market_data evidence overlaps this event's assets",
            }

        latest = max(matches, key=lambda row: _row_get(row, "fetched_at") or "")
        latest_seen = _parse_dt(_row_get(latest, "fetched_at"))
        age_seconds = max(0, int((event_last_seen - latest_seen).total_seconds()))
        return {
            "status": "available",
            "asset_scope": assets,
            "matched_assets": sorted({asset for row in matches for asset in asset_set.intersection(_loads(_row_get(row, "asset_scope_json"), []))}),
            "source_ids": sorted({_row_get(row, "source_id") for row in matches if _row_get(row, "source_id")}),
            "document_ids": [_row_get(row, "document_id") for row in matches[:5] if _row_get(row, "document_id")],
            "latest_market_seen_at": latest_seen.isoformat(),
            "age_seconds_vs_event": age_seconds,
        }

    def _conflicts(self, docs: Sequence[Any]) -> list[str]:
        if len(docs) < 2:
            return []
        positive_hits = set()
        negative_hits = set()
        for row in docs:
            text = f"{_row_get(row, 'title') or ''} {_row_get(row, 'text') or ''}".lower()
            positive_hits.update(term for term in POSITIVE_TERMS if term in text)
            negative_hits.update(term for term in NEGATIVE_TERMS if term in text)

        flags: list[str] = []
        if positive_hits and negative_hits:
            flags.append("mixed_directional_language")
        if len({_row_get(row, "source_id") for row in docs}) > 1 and self._titles_diverge(docs):
            flags.append("source_titles_diverge")
        return flags

    def _generic_landing_page(self, docs: Sequence[Any]) -> bool:
        if not docs:
            return False
        generic_hits = 0
        for row in docs:
            title = str(_row_get(row, "title") or "").strip().lower()
            title_key = str(_row_get(row, "title_key") or "").strip().lower()
            if title.startswith("<!doctype") or title_key in GENERIC_TITLE_KEYS:
                generic_hits += 1
                continue
            if any(pattern in title_key for pattern in GENERIC_TITLE_PATTERNS):
                generic_hits += 1
                continue
            if title_key and len(title_key.split()) <= 2 and title_key in {"news", "releases", "announcements"}:
                generic_hits += 1
        return generic_hits == len(docs)

    def _weak_topic_match(self, docs: Sequence[Any]) -> bool:
        checked = 0
        weak = 0
        for row in docs:
            category = _row_get(row, "category")
            if category not in TOPIC_SENSITIVE_CATEGORIES:
                continue
            checked += 1
            title = str(_row_get(row, "title") or "")
            haystack = title.lower()
            if not any(term in haystack for term in TOPIC_TERMS):
                weak += 1
        return checked > 0 and checked == weak

    def _titles_diverge(self, docs: Sequence[Any]) -> bool:
        title_keys = {_row_get(row, "title_key") for row in docs if _row_get(row, "title_key")}
        if len(title_keys) <= 1:
            return False
        tokens = [set(str(key).split()) for key in title_keys]
        if len(tokens) < 2:
            return False
        common = set.intersection(*tokens)
        union = set.union(*tokens)
        if not union:
            return False
        return len(common) / len(union) < 0.25

    def _review_flags(
        self,
        docs: Sequence[Any],
        source_ids: list[str],
        tiers: Counter,
        categories: Counter,
        avg_trust: float,
        market_confirmation: dict[str, Any],
        conflicts: list[str],
        generic_landing_page: bool,
        weak_topic_match: bool,
    ) -> list[str]:
        flags: list[str] = []
        if generic_landing_page:
            flags.append("generic_landing_or_index_page")
        if weak_topic_match:
            flags.append("weak_topic_match")
        if len(source_ids) < 2:
            flags.append("single_source")
        if tiers.get("T1", 0) == 0:
            flags.append("no_t1_official_confirmation")
        if avg_trust < 0.6:
            flags.append("low_average_source_trust")
        if market_confirmation.get("status") == "missing":
            flags.append("missing_market_confirmation")
        if conflicts:
            flags.append("needs_conflict_review")
        if categories and set(categories) <= {"social", "social_fetch", "opinion"}:
            flags.append("social_only")
        if mean([len(_row_get(row, "text") or "") for row in docs]) < 300:
            flags.append("thin_content")
        return flags

    def _quality_score(
        self,
        docs: Sequence[Any],
        source_ids: list[str],
        tiers: Counter,
        avg_trust: float,
        market_confirmation: dict[str, Any],
        conflicts: list[str],
        last_seen: datetime,
        generic_landing_page: bool,
        weak_topic_match: bool,
    ) -> float:
        score = avg_trust * 0.52
        score += min(0.18, max(0, len(source_ids) - 1) * 0.06)
        score += min(0.12, max(0, len(docs) - 1) * 0.03)
        score += 0.16 if tiers.get("T1", 0) else 0.0
        score += 0.07 if market_confirmation.get("status") == "available" else 0.0
        score += self._freshness_bonus(last_seen)
        score -= 0.10 if conflicts else 0.0
        score -= 0.45 if generic_landing_page else 0.0
        score -= 0.16 if weak_topic_match else 0.0
        return round(max(0.05, min(0.98, score)), 3)

    def _freshness_bonus(self, last_seen: datetime) -> float:
        age_hours = (datetime.now(timezone.utc) - last_seen).total_seconds() / 3600
        if age_hours <= 6:
            return 0.05
        if age_hours <= 24:
            return 0.03
        return 0.0

    def _confirmation_state(
        self,
        quality_score: float,
        tiers: Counter,
        source_count: int,
        market_confirmation: dict[str, Any],
    ) -> str:
        if quality_score >= 0.8 and tiers.get("T1", 0) and source_count >= 2:
            return "confirmed-by-official-and-secondary"
        if quality_score >= 0.68 and (tiers.get("T1", 0) or source_count >= 2):
            return "likely"
        if market_confirmation.get("status") == "available" and quality_score >= 0.55:
            return "market-context-ready"
        if quality_score >= 0.45:
            return "watch"
        return "weak"

    def _priority(self, quality_score: float, confirmation_state: str, categories: Counter) -> str:
        high_impact_categories = {"energy", "macro", "macro_data", "geopolitics", "policy"}
        high_impact = bool(high_impact_categories.intersection(categories))
        if quality_score >= 0.78 and (high_impact or confirmation_state.startswith("confirmed")):
            return "P0"
        if quality_score >= 0.62:
            return "P1"
        if quality_score >= 0.42:
            return "P2"
        return "P3"

    def _followups(self, review_flags: Iterable[str], conflicts: list[str]) -> list[str]:
        flags = set(review_flags)
        followups: list[str] = []
        if "generic_landing_or_index_page" in flags:
            followups.append("Replace this landing/index page with concrete release or article detail URLs.")
        if "weak_topic_match" in flags:
            followups.append("Confirm the item is actually relevant to the watchlist before AI analysis.")
        if "single_source" in flags:
            followups.append("Find at least one independent secondary source for the same event.")
        if "no_t1_official_confirmation" in flags:
            followups.append("Search official or primary sources before treating this as confirmed.")
        if "missing_market_confirmation" in flags:
            followups.append("Fetch fresh market data for mapped assets and attach it to the event.")
        if conflicts:
            followups.append("Review mixed directional language before AI summarization.")
        if "thin_content" in flags:
            followups.append("Use Firecrawl scrape or another full-text source to replace thin evidence.")
        return followups


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
