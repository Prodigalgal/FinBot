from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from finbot.ingestion.models import FetchJob
from finbot.research.readiness_gate import ResearchReadinessGate
from finbot.storage.sqlite_store import SQLiteStore


class CorroborationPlanner:
    def __init__(self, store: SQLiteStore):
        self.store = store
        self.readiness_gate = ResearchReadinessGate()

    def plan(self, max_jobs: int = 20, enqueue: bool = True) -> dict[str, Any]:
        self.store.init_schema()
        events = [
            self._event_row(row)
            for row in self.store.list_event_candidates()
        ]
        annotated = self.readiness_gate.annotate(events)
        jobs: list[FetchJob] = []
        source_events = []
        for event in annotated:
            if event["research_readiness"] not in {"needs-corroboration", "watch-only"}:
                continue
            for job in self._jobs_for_event(event):
                jobs.append(job)
                source_events.append({"event_id": event["event_id"], "event_title": event["title"], "job_id": job.job_id})
                if len(jobs) >= max_jobs:
                    break
            if len(jobs) >= max_jobs:
                break

        if enqueue:
            for job in jobs:
                self.store.upsert_fetch_job(job, status="queued-corroboration", detail="Phase 2 corroboration follow-up")

        return {
            "jobs_planned": len(jobs),
            "enqueued": enqueue,
            "jobs": [job.model_dump(mode="json") for job in jobs],
            "source_events": source_events,
        }

    def _jobs_for_event(self, event: dict[str, Any]) -> list[FetchJob]:
        quality = event.get("quality") or {}
        flags = set(quality.get("review_flags") or [])
        jobs: list[FetchJob] = []
        title = str(event.get("title") or "").strip()
        asset_terms = " ".join(event.get("asset_scope") or [])
        base_query = self._query_text(event, title, asset_terms)

        if "no_t1_official_confirmation" in flags:
            jobs.append(self._search_job(event, f"{base_query} official source release"))
        if "single_source" in flags:
            jobs.append(self._search_job(event, f"{base_query} Reuters AP CNBC official"))
        if "needs_conflict_review" in flags:
            jobs.append(self._search_job(event, f"{base_query} conflicting reports market reaction"))
        if "missing_market_confirmation" in flags:
            jobs.append(self._market_job(event))
        if not jobs and event["research_readiness"] == "watch-only":
            jobs.append(self._search_job(event, f"{base_query} latest update"))
        return jobs

    def _query_text(self, event: dict[str, Any], title: str, asset_terms: str) -> str:
        key_terms = str(event.get("event_key") or "").replace(":", " ")
        text = " ".join(part for part in [title, asset_terms, key_terms] if part)
        return " ".join(text.split()[:18])

    def _search_job(self, event: dict[str, Any], query: str) -> FetchJob:
        return FetchJob(
            source_id="search_firecrawl_global",
            mode="firecrawl_search_then_scrape",
            priority="P1" if event.get("research_readiness") == "needs-corroboration" else "P2",
            asset_scope=event.get("asset_scope") or [],
            job_type="firecrawl_search_then_scrape",
            query=query,
            provider="firecrawl",
            scheduled_at=datetime.now(timezone.utc),
            max_results=5,
            max_scrape_targets=2,
        )

    def _market_job(self, event: dict[str, Any]) -> FetchJob:
        return FetchJob(
            source_id="market_bybit_public",
            mode="exchange_public_api",
            priority="P1",
            asset_scope=event.get("asset_scope") or [],
            job_type="exchange_public_api",
            provider="bybit",
            scheduled_at=datetime.now(timezone.utc),
        )

    def _event_row(self, row) -> dict[str, Any]:
        metadata = _loads(row["metadata_json"], {})
        return {
            "event_id": row["event_id"],
            "event_key": row["event_key"],
            "title": row["title"],
            "category": row["category"],
            "asset_scope": _loads(row["asset_scope_json"], []),
            "source_ids": _loads(row["source_ids_json"], []),
            "priority": metadata.get("priority"),
            "confirmation_state": metadata.get("confirmation_state"),
            "quality": {
                "score": metadata.get("quality_score"),
                "review_flags": metadata.get("review_flags", []),
                "conflict_flags": metadata.get("conflict_flags", []),
                "suggested_followups": metadata.get("suggested_followups", []),
            },
            "market_confirmation": metadata.get("market_confirmation", {}),
        }


def queued_corroboration_jobs(store: SQLiteStore) -> list[dict[str, Any]]:
    with store.connect() as conn:
        rows = conn.execute(
            "select * from fetch_jobs where status = ? order by updated_at desc",
            ("queued-corroboration",),
        ).fetchall()
    return [
        {
            "job_id": row["job_id"],
            "source_id": row["source_id"],
            "mode": row["mode"],
            "priority": row["priority"],
            "job_type": row["job_type"],
            "query": row["query"],
            "url": row["url"],
            "asset_scope": _loads(row["asset_scope_json"], []),
            "scheduled_at": row["scheduled_at"],
            "status": row["status"],
            "detail": row["detail"],
        }
        for row in rows
    ]


def _loads(value: str | None, default):
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
