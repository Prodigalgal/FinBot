from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from typing import Any

from finbot.ingestion.models import FetchJob
from finbot.storage.sqlite_store import SQLiteStore


DISPATCHABLE_DECISIONS = {"needs-followup", "manual-review"}
FETCH_JOB_STATUS = "queued-research-followup"
WORKFLOW_QUERY_TERMS = {
    "needs-followup",
    "needs-corroboration",
    "research-ready",
    "watch-only",
    "fresh",
    "stale-context-only",
    "active-watch",
    "manual-review",
    "archive-background",
    "P0",
    "P1",
    "P2",
    "P3",
}
CHANNEL_QUERY_TERMS = {
    "cross-asset",
    "crypto-risk",
    "equity-risk",
    "precious-metals",
    "rates-and-dollar",
    "energy",
}
ASSET_PATTERN = re.compile(r"^[A-Z0-9_]{2,12}$")
SOURCE_BY_OFFICIAL_HINT = (
    ({"weekly petroleum", "petroleum status", "inventory", "crude", "opec", "oil"}, "official_eia_weekly_petroleum"),
    ({"sanction", "ofac", "treasury", "iran"}, "official_us_treasury_sanctions"),
    ({"state department", "geopolitic", "foreign policy"}, "official_state_department_rss"),
    ({"white house", "president", "executive"}, "official_white_house"),
    ({"fed", "federal reserve", "inflation", "cpi", "rates", "macro"}, "official_federal_reserve"),
)
SECONDARY_SOURCE_BY_HINT = (
    ({"sanction", "iran", "geopolitic", "foreign policy"}, "news_reuters_search"),
    ({"bitcoin", "ethereum", "crypto", "btc", "eth"}, "news_coindesk_search"),
    ({"oil", "crude", "opec", "petroleum", "energy"}, "news_reuters_search"),
    ({"apple", "nasdaq", "earnings", "equity", "macro"}, "news_cnbc_search"),
)


class ResearchFollowupDispatcher:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def dispatch_all(
        self,
        limit_decisions: int | None = None,
        max_jobs: int = 50,
        clear_existing: bool = False,
        pipeline_run_id: str | None = None,
        input_pipeline_run_id: str | None = None,
        idempotent_outputs: bool = True,
    ) -> dict[str, Any]:
        cleared_fetch_jobs = 0
        if clear_existing:
            self.store.clear_research_followup_dispatches()
            cleared_fetch_jobs = self.store.delete_fetch_jobs_by_status(FETCH_JOB_STATUS)
        elif pipeline_run_id and idempotent_outputs:
            self.store.clear_research_followup_dispatches(pipeline_run_id=pipeline_run_id)
        decisions = self._latest_decisions(limit_decisions, pipeline_run_id=input_pipeline_run_id)
        dispatches: list[dict[str, Any]] = []
        skipped: list[dict[str, Any]] = []

        for decision in decisions:
            if decision["decision"] not in DISPATCHABLE_DECISIONS:
                skipped.append({"decision_id": decision["decision_id"], "card_id": decision["card_id"], "reason": "decision_not_dispatchable"})
                continue
            followups = decision.get("follow_up_jobs") or []
            if not followups:
                skipped.append({"decision_id": decision["decision_id"], "card_id": decision["card_id"], "reason": "no_follow_up_jobs"})
                continue
            for index, followup in enumerate(followups):
                if len(dispatches) >= max_jobs:
                    skipped.append({"decision_id": decision["decision_id"], "card_id": decision["card_id"], "reason": "max_jobs_reached"})
                    break
                job = self._job_for_followup(decision, followup, index)
                self.store.upsert_fetch_job(job, status=FETCH_JOB_STATUS, detail=self._detail(decision, followup))
                dispatch = self._dispatch_record(decision, followup, job, pipeline_run_id=pipeline_run_id)
                self.store.insert_research_followup_dispatch(dispatch)
                dispatches.append(dispatch)

        return {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "decision_count": len(decisions),
            "jobs_dispatched": len(dispatches),
            "cleared_fetch_jobs": cleared_fetch_jobs,
            "skipped": skipped,
            "job_status": FETCH_JOB_STATUS,
            "job_types": dict(Counter(dispatch["job_type"] for dispatch in dispatches)),
            "source_ids": dict(Counter(dispatch["source_id"] for dispatch in dispatches)),
            "dispatches": dispatches,
        }

    def _latest_decisions(self, limit: int | None, pipeline_run_id: str | None = None) -> list[dict[str, Any]]:
        latest: dict[str, dict[str, Any]] = {}
        for row in self.store.list_research_card_decisions(limit=None, pipeline_run_id=pipeline_run_id):
            if row["card_id"] in latest:
                continue
            latest[row["card_id"]] = _loads(row["payload_json"], {})
        values = list(latest.values())
        values.sort(key=lambda item: (float(item.get("score") or 0.0), item.get("created_at") or ""), reverse=True)
        if limit:
            return values[:limit]
        return values

    def _job_for_followup(self, decision: dict[str, Any], followup: dict[str, Any], index: int) -> FetchJob:
        detail = str(followup.get("detail") or "")
        detail_lower = detail.lower()
        assets = self._assets_from_decision(decision)
        job_id = self._job_id(decision, followup, index)

        if self._is_market_followup(detail_lower):
            return FetchJob(
                job_id=job_id,
                source_id="market_bybit_public",
                mode="exchange_public_api",
                priority=self._priority(decision),
                asset_scope=assets,
                job_type="exchange_public_api",
                provider="bybit",
                scheduled_at=datetime.now(timezone.utc),
            )

        if self._is_official_followup(detail_lower):
            source_id = self._official_source_id(decision, detail_lower)
            return FetchJob(
                job_id=job_id,
                source_id=source_id,
                mode="firecrawl_scrape",
                priority=self._priority(decision),
                asset_scope=assets,
                job_type="firecrawl_scrape",
                provider="firecrawl",
                scheduled_at=datetime.now(timezone.utc),
                max_results=1,
                max_scrape_targets=1,
            )

        source_id = self._secondary_source_id(decision, detail_lower)
        return FetchJob(
            job_id=job_id,
            source_id=source_id,
            mode="firecrawl_search_then_scrape",
            priority=self._priority(decision),
            asset_scope=assets,
            job_type="firecrawl_search_then_scrape",
            query=self._query(decision, detail),
            provider="firecrawl",
            scheduled_at=datetime.now(timezone.utc),
            max_results=3,
            max_scrape_targets=1,
        )

    def _dispatch_record(self, decision: dict[str, Any], followup: dict[str, Any], job: FetchJob, pipeline_run_id: str | None = None) -> dict[str, Any]:
        created_at = datetime.now(timezone.utc).isoformat()
        if pipeline_run_id:
            dispatch_id = hashlib.sha256(f"{pipeline_run_id}:{decision['decision_id']}:{job.job_id}".encode("utf-8")).hexdigest()
        else:
            dispatch_id = hashlib.sha256(f"{decision['decision_id']}:{job.job_id}:{created_at}".encode("utf-8")).hexdigest()
        return {
            "dispatch_id": dispatch_id,
            "pipeline_run_id": pipeline_run_id,
            "decision_id": decision["decision_id"],
            "card_id": decision["card_id"],
            "event_id": decision["event_id"],
            "event_key": decision.get("event_key"),
            "decision": decision["decision"],
            "score": decision["score"],
            "job_id": job.job_id,
            "source_id": job.source_id,
            "job_type": job.job_type,
            "priority": job.priority,
            "asset_scope": job.asset_scope,
            "query": job.query,
            "status": FETCH_JOB_STATUS,
            "followup": followup,
            "created_at": created_at,
        }

    def _query(self, decision: dict[str, Any], detail: str) -> str:
        words = self._clean_query_terms(decision, detail)
        if "official" in detail.lower() or "t1" in detail.lower():
            return f"{words} official source release"
        if "independent" in detail.lower() or "secondary" in detail.lower():
            return f"{words} Reuters AP CNBC official"
        if "conflict" in detail.lower():
            return f"{words} conflicting reports market reaction"
        return f"{words} latest update"

    def _clean_query_terms(self, decision: dict[str, Any], detail: str) -> str:
        event_key = str(decision.get("event_key") or "")
        detail = _normalize_detail(detail)
        raw_parts = re.split(r"[\s:/,_-]+", f"{event_key} {detail}")
        terms: list[str] = []
        seen: set[str] = set()
        for raw in raw_parts:
            term = raw.strip().strip(".;,()[]{}")
            if not term:
                continue
            lower = term.lower()
            if term in WORKFLOW_QUERY_TERMS or lower in {item.lower() for item in WORKFLOW_QUERY_TERMS}:
                continue
            if lower in CHANNEL_QUERY_TERMS:
                continue
            if ASSET_PATTERN.match(term) and term.upper() not in {"OPEC", "CPI", "SEC", "ETF"}:
                continue
            if lower in {"same", "event", "this", "that", "before", "treating", "confirmed", "find", "least", "one", "source", "sources", "market", "news"}:
                continue
            if lower in seen:
                continue
            terms.append(term)
            seen.add(lower)
            if len(terms) >= 12:
                break
        if not terms:
            return " ".join(str(decision.get("event_key") or "market event").replace(":", " ").split()[:8])
        return " ".join(terms)

    def _assets_from_decision(self, decision: dict[str, Any]) -> list[str]:
        assets: list[str] = []
        for tag in decision.get("watchlist_tags") or []:
            value = str(tag)
            if value.isupper() and len(value) <= 12 and value not in {"P0", "P1", "P2", "P3"}:
                assets.append(value)
        return sorted(set(assets))

    def _priority(self, decision: dict[str, Any]) -> str:
        priority = decision.get("priority")
        if priority in {"P0", "P1", "P2", "P3"}:
            return priority
        if float(decision.get("score") or 0.0) >= 70:
            return "P1"
        return "P2"

    def _detail(self, decision: dict[str, Any], followup: dict[str, Any]) -> str:
        return f"Phase 3 research follow-up from card {decision['card_id']}: {followup.get('detail')}"

    def _job_id(self, decision: dict[str, Any], followup: dict[str, Any], index: int) -> str:
        value = f"phase3-followup:{decision['card_id']}:{index}:{followup.get('detail')}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def _is_market_followup(self, detail_lower: str) -> bool:
        return "market" in detail_lower or "行情" in detail_lower or "snapshot" in detail_lower

    def _is_official_followup(self, detail_lower: str) -> bool:
        return "official" in detail_lower or "primary" in detail_lower or "t1" in detail_lower or "正式" in detail_lower

    def _official_source_id(self, decision: dict[str, Any], detail_lower: str) -> str:
        haystack = f"{decision.get('event_key') or ''} {detail_lower}".lower()
        for hints, source_id in SOURCE_BY_OFFICIAL_HINT:
            if any(hint in haystack for hint in hints):
                return source_id
        return "official_govinfo_rss"

    def _secondary_source_id(self, decision: dict[str, Any], detail_lower: str) -> str:
        haystack = f"{decision.get('event_key') or ''} {detail_lower}".lower()
        for hints, source_id in SECONDARY_SOURCE_BY_HINT:
            if any(hint in haystack for hint in hints):
                return source_id
        return "search_firecrawl_global"


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _normalize_detail(detail: str) -> str:
    aliases = {
        "needs_independent_secondary_source": "independent secondary source",
        "needs_t1_official_confirmation": "official primary confirmation",
        "Search official or primary sources before treating this as confirmed.": "official primary confirmation",
        "Find at least one independent secondary source for the same event.": "independent secondary source",
    }
    return aliases.get(detail, detail)
