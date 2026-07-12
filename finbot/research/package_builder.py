from __future__ import annotations

import json
from datetime import datetime, timezone
from uuid import uuid4

from finbot.ingestion.models import AdapterResult
from finbot.research.compression import InformationCompressionPlanner
from finbot.research.readiness_gate import READY_GROUPS, ResearchReadinessGate, payload_group_key
from finbot.scheduling.corroboration import queued_corroboration_jobs
from finbot.storage.sqlite_store import SQLiteStore


def build_research_input(results: list[AdapterResult], time_window: str = "smoke") -> dict:
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "time_window": time_window,
        "asset_scope": sorted({asset for result in results for asset in result.metadata.get("asset_scope", [])}),
        "source_health": {
            result.source_id: {
                "status": result.status,
                "detail": result.detail,
                "required_keys": result.required_keys,
            }
            for result in results
        },
        "raw_document_refs": [
            {
                "source_id": result.source_id,
                "evidence_id": result.evidence.evidence_id,
                "url": result.evidence.url,
                "query": result.evidence.query,
            }
            for result in results
            if result.evidence is not None
        ],
        "event_candidates": [],
        "market_context": {},
    }


class ResearchPackageBuilder:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def build_from_store(self, time_window: str = "latest", limit_events: int = 50, limit_documents: int = 100) -> tuple[str, dict]:
        events = self.store.list_event_candidates(limit=limit_events)
        documents = self.store.list_normalized_documents(limit=limit_documents)
        health = self._source_health()
        event_rows = [self._event_row(row) for row in events]
        readiness_gate = ResearchReadinessGate()
        annotated_events = readiness_gate.annotate(event_rows)
        readiness_groups = readiness_gate.group(event_rows)
        payload = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "time_window": time_window,
            "asset_scope": self._asset_scope(documents, events),
            "market_context": self._market_context_stub(documents),
            "market_context_snapshots": self._market_context_snapshots(),
            "macro_release_facts": self._macro_release_facts(),
            "corroboration_queue": self._corroboration_queue(),
            "budget_state": self._budget_state(),
            "official_calendar": self._official_calendar(),
            "quality_summary": self._quality_summary(events, annotated_events, readiness_gate),
            "compression_plan": InformationCompressionPlanner().build(documents, annotated_events),
            "ai_compressions": self._ai_compressions(),
            "event_candidates": annotated_events,
            **{
                payload_group_key(group): readiness_groups[group]
                for group in READY_GROUPS
            },
            "raw_document_refs": [self._document_row(row) for row in documents if row["category"] != "market_data"],
            "source_health": health,
            "constraints": {
                "no_trading": True,
                "no_order_execution": True,
                "sentiment_is_not_signal": True,
                "research_only": True,
            },
        }
        package_id = uuid4().hex
        self.store.insert_research_package(package_id, time_window, payload)
        return package_id, payload

    def _source_health(self) -> dict:
        with self.store.connect() as conn:
            rows = conn.execute("select * from source_health order by source_id").fetchall()
        return {
            row["source_id"]: {
                "status": row["status"],
                "detail": row["detail"],
                "success": bool(row["success"]),
                "required_keys": self._loads(row["required_keys_json"], []),
                "checked_at": row["checked_at"],
            }
            for row in rows
        }

    def _event_row(self, row) -> dict:
        metadata = self._loads(row["metadata_json"], {})
        return {
            "event_id": row["event_id"],
            "event_key": row["event_key"],
            "title": row["title"],
            "category": row["category"],
            "asset_scope": self._loads(row["asset_scope_json"], []),
            "document_ids": self._loads(row["document_ids_json"], []),
            "source_ids": self._loads(row["source_ids_json"], []),
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

    def _document_row(self, row) -> dict:
        return {
            "document_id": row["document_id"],
            "evidence_id": row["evidence_id"],
            "source_id": row["source_id"],
            "tier": row["tier"],
            "category": row["category"],
            "trust_weight": row["trust_weight"],
            "canonical_url": row["canonical_url"],
            "title": row["title"],
            "fetched_at": row["fetched_at"],
            "language": row["language"],
            "content_hash": row["content_hash"],
            "asset_scope": self._loads(row["asset_scope_json"], []),
        }

    def _asset_scope(self, documents, events) -> list[str]:
        assets = set()
        for row in documents:
            assets.update(self._loads(row["asset_scope_json"], []))
        for row in events:
            assets.update(self._loads(row["asset_scope_json"], []))
        return sorted(assets)

    def _market_context_stub(self, documents) -> dict:
        market_docs = [row for row in documents if row["category"] == "market_data"]
        return {
            row["source_id"]: {
                "document_id": row["document_id"],
                "title": row["title"],
                "fetched_at": row["fetched_at"],
                "asset_scope": self._loads(row["asset_scope_json"], []),
                "text_preview": (row["text"] or "")[:500],
            }
            for row in market_docs[:20]
        }

    def _official_calendar(self) -> dict:
        rows = self.store.list_official_releases()
        releases = [self._release_row(row) for row in rows]
        statuses: dict[str, int] = {}
        for release in releases:
            status = release["status"]
            statuses[status] = statuses.get(status, 0) + 1
        return {
            "total_releases": len(releases),
            "statuses": dict(sorted(statuses.items())),
            "releases": releases,
        }

    def _market_context_snapshots(self) -> dict:
        rows = self.store.list_market_context_snapshots()
        snapshots = [self._market_snapshot_row(row) for row in rows]
        statuses: dict[str, int] = {}
        for snapshot in snapshots:
            status = snapshot["status"]
            statuses[status] = statuses.get(status, 0) + 1
        return {
            "total_snapshots": len(snapshots),
            "statuses": dict(sorted(statuses.items())),
            "snapshots": snapshots,
        }

    def _macro_release_facts(self) -> dict:
        rows = self.store.list_macro_release_facts()
        facts = [self._macro_fact_row(row) for row in rows]
        providers: dict[str, int] = {}
        release_types: dict[str, int] = {}
        for fact in facts:
            providers[fact["provider"]] = providers.get(fact["provider"], 0) + 1
            release_types[fact["release_type"]] = release_types.get(fact["release_type"], 0) + 1
        return {
            "total_facts": len(facts),
            "providers": dict(sorted(providers.items())),
            "release_types": dict(sorted(release_types.items())),
            "facts": facts,
        }

    def _corroboration_queue(self) -> dict:
        jobs = queued_corroboration_jobs(self.store)
        return {
            "total_jobs": len(jobs),
            "jobs": jobs,
        }

    def _budget_state(self) -> dict:
        rows = self.store.list_source_budget_state()
        states = [self._budget_row(row) for row in rows]
        statuses: dict[str, int] = {}
        for state in states:
            statuses[state["status"]] = statuses.get(state["status"], 0) + 1
        return {
            "total_sources": len(states),
            "statuses": dict(sorted(statuses.items())),
            "states": states,
        }

    def _ai_compressions(self) -> dict:
        rows = self.store.list_ai_compressions(limit=100)
        compressions = [self._ai_compression_row(row) for row in rows]
        statuses: dict[str, int] = {}
        providers: dict[str, int] = {}
        target_types: dict[str, int] = {}
        for compression in compressions:
            statuses[compression["status"]] = statuses.get(compression["status"], 0) + 1
            providers[compression["provider"]] = providers.get(compression["provider"], 0) + 1
            target_types[compression["target_type"]] = target_types.get(compression["target_type"], 0) + 1
        return {
            "policy": "ai-compression-is-context-only-not-fact-source",
            "total_compressions": len(compressions),
            "statuses": dict(sorted(statuses.items())),
            "providers": dict(sorted(providers.items())),
            "target_types": dict(sorted(target_types.items())),
            "compressions": compressions,
        }

    def _budget_row(self, row) -> dict:
        return {
            "source_id": row["source_id"],
            "provider": row["provider"],
            "budget_window": row["budget_window"],
            "requests_used": row["requests_used"],
            "credits_used": row["credits_used"],
            "max_requests": row["max_requests"],
            "max_credits": row["max_credits"],
            "throttled_until": row["throttled_until"],
            "last_error": row["last_error"],
            "status": row["status"],
            "metadata": self._loads(row["metadata_json"], {}),
            "updated_at": row["updated_at"],
        }

    def _macro_fact_row(self, row) -> dict:
        return {
            "fact_id": row["fact_id"],
            "source_id": row["source_id"],
            "evidence_id": row["evidence_id"],
            "provider": row["provider"],
            "release_type": row["release_type"],
            "observed_at": row["observed_at"],
            "fields": self._loads(row["fields_json"], {}),
            "asset_scope": self._loads(row["asset_scope_json"], []),
            "confidence": row["confidence"],
            "notes": self._loads(row["notes_json"], []),
            "metadata": self._loads(row["metadata_json"], {}),
        }

    def _market_snapshot_row(self, row) -> dict:
        return {
            "snapshot_id": row["snapshot_id"],
            "event_id": row["event_id"],
            "event_key": row["event_key"],
            "asset_scope": self._loads(row["asset_scope_json"], []),
            "provider": row["provider"],
            "captured_at": row["captured_at"],
            "status": row["status"],
            "market_document_ids": self._loads(row["market_document_ids_json"], []),
            "market_source_ids": self._loads(row["market_source_ids_json"], []),
            "price_change_pct": row["price_change_pct"],
            "volume_change_pct": row["volume_change_pct"],
            "volatility_proxy": row["volatility_proxy"],
            "note": row["note"],
        }

    def _ai_compression_row(self, row) -> dict:
        return {
            "compression_id": row["compression_id"],
            "target_type": row["target_type"],
            "target_id": row["target_id"],
            "provider": row["provider"],
            "protocol": row["protocol"],
            "model": row["model"],
            "status": row["status"],
            "summary": self._loads(row["summary_json"], {}),
            "prompt_hash": row["prompt_hash"],
            "source_refs": self._loads(row["source_refs_json"], []),
            "error": row["error"],
            "created_at": row["created_at"],
        }

    def _release_row(self, row) -> dict:
        metadata = self._loads(row["metadata_json"], {})
        return {
            "release_id": row["release_id"],
            "provider": row["provider"],
            "release_type": row["release_type"],
            "title": row["title"],
            "scheduled_at": row["scheduled_at"],
            "timezone": row["timezone"],
            "asset_scope": self._loads(row["asset_scope_json"], []),
            "expected_fields": self._loads(row["expected_fields_json"], []),
            "source_url": row["source_url"],
            "status": row["status"],
            "matched_document_ids": metadata.get("matched_document_ids", []),
            "candidate_document_ids": metadata.get("candidate_document_ids", []),
            "metadata": metadata,
        }

    def _quality_summary(self, events, event_rows: list[dict] | None = None, readiness_gate: ResearchReadinessGate | None = None) -> dict:
        priorities: dict[str, int] = {}
        confirmation_states: dict[str, int] = {}
        review_flags: dict[str, int] = {}
        for row in events:
            metadata = self._loads(row["metadata_json"], {})
            priority = metadata.get("priority") or "unknown"
            priorities[priority] = priorities.get(priority, 0) + 1
            state = metadata.get("confirmation_state") or "unknown"
            confirmation_states[state] = confirmation_states.get(state, 0) + 1
            for flag in metadata.get("review_flags", []):
                review_flags[flag] = review_flags.get(flag, 0) + 1
        summary = {
            "total_events": len(events),
            "priorities": dict(sorted(priorities.items())),
            "confirmation_states": dict(sorted(confirmation_states.items())),
            "review_flags": dict(sorted(review_flags.items())),
        }
        if event_rows is not None:
            gate = readiness_gate or ResearchReadinessGate()
            summary["research_readiness"] = gate.summary(event_rows)
        return summary

    def _loads(self, value: str | None, default):
        if not value:
            return default
        try:
            return json.loads(value)
        except Exception:
            return default
