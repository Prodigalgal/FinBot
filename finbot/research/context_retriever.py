from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from finbot.research.freshness import FreshnessGate, document_ref
from finbot.research.readiness_gate import ResearchReadinessGate
from finbot.storage.sqlite_store import SQLiteStore


DEFAULT_RESEARCH_READINESS = ("research-ready", "needs-corroboration")


@dataclass(frozen=True)
class ResearchContextPack:
    event: dict[str, Any]
    freshness_policy: dict[str, Any]
    fresh_evidence: list[dict[str, Any]]
    stale_context: list[dict[str, Any]]
    discarded_stale_refs: list[dict[str, Any]]
    macro_context: list[dict[str, Any]]
    market_context: list[dict[str, Any]]
    ai_compressions: list[dict[str, Any]]
    missing_context: list[str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "event": self.event,
            "freshness_policy": self.freshness_policy,
            "fresh_evidence": self.fresh_evidence,
            "stale_context": self.stale_context,
            "discarded_stale_refs": self.discarded_stale_refs,
            "macro_context": self.macro_context,
            "market_context": self.market_context,
            "ai_compressions": self.ai_compressions,
            "missing_context": self.missing_context,
        }


class SQLiteResearchContextRetriever:
    def __init__(self, store: SQLiteStore, freshness_gate: FreshnessGate | None = None):
        self.store = store
        self.freshness_gate = freshness_gate or FreshnessGate()

    def candidate_events(
        self,
        limit: int = 20,
        readiness: tuple[str, ...] = DEFAULT_RESEARCH_READINESS,
    ) -> list[dict[str, Any]]:
        events = [_event_row(row) for row in self.store.list_event_candidates(limit=None)]
        annotated = ResearchReadinessGate().annotate(events)
        selected = [event for event in annotated if event.get("research_readiness") in readiness]
        selected.sort(key=_event_sort_key, reverse=True)
        return selected[:limit]

    def build_context_pack(self, event: dict[str, Any]) -> ResearchContextPack:
        document_ids = set(event.get("document_ids") or [])
        documents = [row for row in self.store.list_normalized_documents(limit=None) if row["document_id"] in document_ids]
        fresh_evidence: list[dict[str, Any]] = []
        stale_context: list[dict[str, Any]] = []
        discarded_stale_refs: list[dict[str, Any]] = []

        for row in documents:
            decision = self.freshness_gate.classify_document(row)
            ref = document_ref(row, decision)
            if decision.status == "fresh":
                fresh_evidence.append(ref)
            elif decision.status == "stale-context":
                stale_context.append(ref)
            else:
                discarded_stale_refs.append(ref)

        macro_context = self._macro_context(event)
        market_context = self._market_context(event)
        ai_compressions = self._ai_compressions(event)
        missing_context = self._missing_context(event, fresh_evidence, macro_context, market_context)
        return ResearchContextPack(
            event=event,
            freshness_policy=self.freshness_gate.policy(),
            fresh_evidence=fresh_evidence,
            stale_context=stale_context,
            discarded_stale_refs=discarded_stale_refs,
            macro_context=macro_context,
            market_context=market_context,
            ai_compressions=ai_compressions,
            missing_context=missing_context,
        )

    def _macro_context(self, event: dict[str, Any]) -> list[dict[str, Any]]:
        event_assets = set(event.get("asset_scope") or [])
        rows = self.store.list_macro_release_facts()
        context: list[dict[str, Any]] = []
        for row in rows:
            asset_scope = set(_loads(row["asset_scope_json"], []))
            if event_assets and not event_assets.intersection(asset_scope):
                continue
            context.append(
                {
                    "fact_id": row["fact_id"],
                    "source_id": row["source_id"],
                    "evidence_id": row["evidence_id"],
                    "provider": row["provider"],
                    "release_type": row["release_type"],
                    "observed_at": row["observed_at"],
                    "fields": _loads(row["fields_json"], {}),
                    "asset_scope": sorted(asset_scope),
                    "confidence": row["confidence"],
                    "notes": _loads(row["notes_json"], []),
                }
            )
        return context[:10]

    def _market_context(self, event: dict[str, Any]) -> list[dict[str, Any]]:
        event_assets = set(event.get("asset_scope") or [])
        rows = self.store.list_market_context_snapshots()
        context: list[dict[str, Any]] = []
        for row in rows:
            row_assets = set(_loads(row["asset_scope_json"], []))
            direct_match = row["event_id"] == event["event_id"]
            asset_match = bool(event_assets.intersection(row_assets))
            if not direct_match and not asset_match:
                continue
            context.append(
                {
                    "snapshot_id": row["snapshot_id"],
                    "event_id": row["event_id"],
                    "provider": row["provider"],
                    "captured_at": row["captured_at"],
                    "status": row["status"],
                    "asset_scope": sorted(row_assets),
                    "market_document_ids": _loads(row["market_document_ids_json"], []),
                    "market_source_ids": _loads(row["market_source_ids_json"], []),
                    "price_change_pct": row["price_change_pct"],
                    "volume_change_pct": row["volume_change_pct"],
                    "volatility_proxy": row["volatility_proxy"],
                    "note": row["note"],
                    "match_type": "event_id" if direct_match else "asset_scope",
                }
            )
        return context[:8]

    def _ai_compressions(self, event: dict[str, Any]) -> list[dict[str, Any]]:
        target_ids = {event["event_id"], *(event.get("document_ids") or [])}
        context: list[dict[str, Any]] = []
        for row in self.store.list_ai_compressions(limit=200):
            if row["target_id"] not in target_ids:
                continue
            context.append(
                {
                    "compression_id": row["compression_id"],
                    "target_type": row["target_type"],
                    "target_id": row["target_id"],
                    "provider": row["provider"],
                    "protocol": row["protocol"],
                    "model": row["model"],
                    "status": row["status"],
                    "summary": _loads(row["summary_json"], {}),
                    "source_refs": _loads(row["source_refs_json"], []),
                    "created_at": row["created_at"],
                }
            )
        return context[:10]

    def _missing_context(
        self,
        event: dict[str, Any],
        fresh_evidence: list[dict[str, Any]],
        macro_context: list[dict[str, Any]],
        market_context: list[dict[str, Any]],
    ) -> list[str]:
        missing: list[str] = []
        quality = event.get("quality") or {}
        flags = set(quality.get("review_flags") or [])
        if not fresh_evidence:
            missing.append("no_fresh_primary_evidence")
        if "single_source" in flags:
            missing.append("needs_independent_secondary_source")
        if "no_t1_official_confirmation" in flags:
            missing.append("needs_t1_official_confirmation")
        if "missing_market_confirmation" in flags and not market_context:
            missing.append("needs_market_context_snapshot")
        if event.get("category") in {"macro", "macro_data", "macro_market_news"} and not macro_context:
            missing.append("needs_structured_macro_fact")
        return missing


def _event_row(row) -> dict[str, Any]:
    metadata = _loads(row["metadata_json"], {})
    return {
        "event_id": row["event_id"],
        "event_key": row["event_key"],
        "title": row["title"],
        "category": row["category"],
        "asset_scope": _loads(row["asset_scope_json"], []),
        "document_ids": _loads(row["document_ids_json"], []),
        "source_ids": _loads(row["source_ids_json"], []),
        "confidence": row["confidence"],
        "first_seen_at": row["first_seen_at"],
        "last_seen_at": row["last_seen_at"],
        "summary": row["summary"],
        "priority": metadata.get("priority"),
        "confirmation_state": metadata.get("confirmation_state"),
        "quality": {
            "score": metadata.get("quality_score"),
            "evidence_tiers": metadata.get("evidence_tiers", {}),
            "source_categories": metadata.get("source_categories", {}),
            "average_trust_weight": metadata.get("average_trust_weight"),
            "review_flags": metadata.get("review_flags", []),
            "conflict_flags": metadata.get("conflict_flags", []),
            "suggested_followups": metadata.get("suggested_followups", []),
        },
        "market_confirmation": metadata.get("market_confirmation", {}),
        "metadata": metadata,
    }


def _event_sort_key(event: dict[str, Any]) -> tuple[float, str, str]:
    priority_rank = {"P0": 4, "P1": 3, "P2": 2, "P3": 1}
    return (
        priority_rank.get(event.get("priority") or "P3", 0) + float((event.get("quality") or {}).get("score") or 0),
        event.get("last_seen_at") or "",
        event.get("event_id") or "",
    )


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
