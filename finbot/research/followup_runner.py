from __future__ import annotations

import json
from collections import Counter, deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from finbot.config.settings import Settings
from finbot.config.source_catalog import SourceCatalog
from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.dispatcher import Dispatcher
from finbot.ingestion.models import AdapterResult, FetchJob, SourceConfig
from finbot.research.card_promotion import ResearchCardPromoter
from finbot.research.card_validator import ResearchCardValidator
from finbot.research.context_retriever import DEFAULT_RESEARCH_READINESS, SQLiteResearchContextRetriever
from finbot.research.followup_dispatch import FETCH_JOB_STATUS
from finbot.research.freshness import FreshnessGate
from finbot.research.research_cards import ResearchCardBuildConfig, ResearchCardBuilder
from finbot.storage.evidence_store import EvidenceStore
from finbot.storage.sqlite_store import SQLiteStore


class ResearchFollowupRunner:
    def __init__(
        self,
        settings: Settings,
        store: SQLiteStore,
        catalog: SourceCatalog,
        topics: TopicWatchlists,
        timeout_seconds: float = 30,
    ):
        self.settings = settings
        self.store = store
        self.catalog = catalog
        self.topics = topics
        self.source_map = {source.id: source for source in catalog.sources}
        self.dispatcher = Dispatcher(settings, EvidenceStore(settings.evidence_dir), topics, timeout_seconds=timeout_seconds)

    async def run(
        self,
        max_jobs: int = 10,
        max_discovered_jobs: int = 10,
        max_discovered_per_result: int = 2,
        dry_run: bool = False,
        force_disabled: bool = False,
        rebuild_after_run: bool = False,
        rebuild_time_window: str = "phase3-followup-refresh",
        rebuild_limit_events: int = 10,
        include_watch_only: bool = False,
        pipeline_run_id: str | None = None,
    ) -> dict[str, Any]:
        self.store.init_schema()
        for source in self.catalog.sources:
            self.store.upsert_source(source)
        queued_rows = self.store.list_fetch_jobs_by_status(FETCH_JOB_STATUS, limit=max_jobs)
        queued_jobs = [self._job_from_row(row) for row in queued_rows]

        if dry_run:
            return {
                "dry_run": True,
                "queued_status": FETCH_JOB_STATUS,
                "queued_jobs": len(queued_jobs),
                "jobs": [job.model_dump(mode="json") for job in queued_jobs],
            }

        queue: deque[tuple[SourceConfig, FetchJob, bool]] = deque()
        skipped: list[dict[str, Any]] = []
        for job in queued_jobs:
            source = self.source_map.get(job.source_id)
            if source is None:
                skipped.append({"job_id": job.job_id, "source_id": job.source_id, "reason": "source_not_found_in_catalog"})
                continue
            throttle_reason = self._source_throttle_reason(source.id)
            if throttle_reason:
                self.store.upsert_fetch_job(job, status="skipped-throttled", detail=throttle_reason)
                skipped.append({"job_id": job.job_id, "source_id": job.source_id, "reason": "source_throttled", "detail": throttle_reason})
                continue
            queue.append((source, job, False))

        results: list[dict[str, Any]] = []
        discovered_executed = 0
        while queue:
            source, job, is_discovered = queue.popleft()
            if is_discovered and discovered_executed >= max_discovered_jobs:
                skipped.append({"job_id": job.job_id, "source_id": job.source_id, "reason": "max_discovered_jobs_reached"})
                continue

            throttle_reason = self._source_throttle_reason(source.id)
            if throttle_reason:
                self.store.upsert_fetch_job(job, status="skipped-throttled", detail=throttle_reason)
                skipped.append({"job_id": job.job_id, "source_id": job.source_id, "reason": "source_throttled", "detail": throttle_reason})
                continue

            self.store.upsert_fetch_job(job, status="running", detail="Phase 3 research follow-up running")
            result = await self.dispatcher.dispatch_job(source, job, force_disabled=force_disabled)
            self._persist_result(job, result)
            results.append(self._result_row(job, result, is_discovered))
            self._maybe_throttle_source(source, result)

            if is_discovered:
                discovered_executed += 1
                continue
            for discovered_job in result.discovered_jobs[:max_discovered_per_result]:
                discovered_source = self.source_map.get(discovered_job.source_id, source)
                queue.append((discovered_source, discovered_job, True))

        status_counts = Counter(result["status"] for result in results)
        return {
            "dry_run": False,
            "queued_status": FETCH_JOB_STATUS,
            "root_jobs_selected": len(queued_jobs),
            "runs_executed": len(results),
            "discovered_executed": discovered_executed,
            "statuses": dict(status_counts),
            "skipped": skipped,
            "results": results,
            "rebuild": self.rebuild_research_cycle(
                time_window=rebuild_time_window,
                limit_events=rebuild_limit_events,
                include_watch_only=include_watch_only,
                pipeline_run_id=pipeline_run_id,
            ) if rebuild_after_run and results else None,
        }

    def _persist_result(self, job: FetchJob, result: AdapterResult) -> None:
        if result.evidence:
            self.store.insert_evidence(result.evidence)
        self.store.insert_fetch_run(job, result)
        self.store.upsert_health(result)

    def _result_row(self, job: FetchJob, result: AdapterResult, is_discovered: bool) -> dict[str, Any]:
        return {
            "job_id": job.job_id,
            "source_id": job.source_id,
            "job_type": job.job_type,
            "query": job.query,
            "url": job.url,
            "status": result.status,
            "success": result.success,
            "detail": result.detail,
            "evidence_id": result.evidence.evidence_id if result.evidence else None,
            "discovered_jobs": len(result.discovered_jobs),
            "is_discovered": is_discovered,
        }

    def _source_throttle_reason(self, source_id: str) -> str | None:
        state = self.store.get_source_budget_state(source_id)
        if state is None:
            return None
        status = str(state["status"] or "")
        throttled_until = _parse_dt(state["throttled_until"])
        if status in {"budget-exhausted", "throttled"}:
            if throttled_until is None or throttled_until > datetime.now(timezone.utc):
                return f"Source {source_id} is {status}: {state['last_error'] or 'budget/backoff active'}"
        if throttled_until and throttled_until > datetime.now(timezone.utc):
            return f"Source {source_id} is throttled until {throttled_until.isoformat()}: {state['last_error'] or 'backoff active'}"
        return None

    def _maybe_throttle_source(self, source: SourceConfig, result: AdapterResult) -> None:
        detail = result.detail or ""
        detail_lower = detail.lower()
        if result.success:
            return
        if "http 429" in detail_lower or "rate limit" in detail_lower or "rate-limited" in detail_lower:
            self.store.mark_source_throttled(source.id, source.provider or source.mode, detail, minutes=45, status="throttled")
            return
        if "proxy pool is required" in detail_lower or "all firecrawl proxy candidates failed" in detail_lower:
            self.store.mark_source_throttled(source.id, source.provider or source.mode, detail, minutes=15, status="throttled")
            return
        if result.status == "blocked-by-provider":
            self.store.mark_source_throttled(source.id, source.provider or source.mode, detail, minutes=10, status="throttled")

    def rebuild_research_cycle(
        self,
        time_window: str,
        limit_events: int = 10,
        include_watch_only: bool = False,
        pipeline_run_id: str | None = None,
    ) -> dict[str, Any]:
        self.store.clear_research_cards(pipeline_run_id=pipeline_run_id)
        self.store.clear_research_card_validations(pipeline_run_id=pipeline_run_id)
        self.store.clear_research_card_decisions(pipeline_run_id=pipeline_run_id)
        readiness = DEFAULT_RESEARCH_READINESS
        if include_watch_only:
            readiness = (*DEFAULT_RESEARCH_READINESS, "watch-only")

        retriever = SQLiteResearchContextRetriever(store=self.store, freshness_gate=FreshnessGate())
        builder = ResearchCardBuilder()
        events = retriever.candidate_events(limit=limit_events, readiness=readiness)
        cards = []
        for event in events:
            context = retriever.build_context_pack(event)
            card = builder.build(context, ResearchCardBuildConfig(time_window=time_window, pipeline_run_id=pipeline_run_id))
            self.store.insert_research_card(card)
            cards.append(card)

        validation_report = ResearchCardValidator(self.store).validate_all(
            clear_existing=pipeline_run_id is None,
            pipeline_run_id=pipeline_run_id,
            input_pipeline_run_id=pipeline_run_id,
        )
        promotion_report = ResearchCardPromoter(self.store).promote_all(
            clear_existing=pipeline_run_id is None,
            pipeline_run_id=pipeline_run_id,
            input_pipeline_run_id=pipeline_run_id,
        )
        return {
            "time_window": time_window,
            "cards_built": len(cards),
            "validation_statuses": validation_report["statuses"],
            "promotion_decisions": promotion_report["decisions"],
        }

    def _job_from_row(self, row: Any) -> FetchJob:
        return FetchJob(
            job_id=row["job_id"],
            source_id=row["source_id"],
            mode=row["mode"],
            priority=row["priority"],
            asset_scope=_loads(row["asset_scope_json"], []),
            job_type=row["job_type"],
            url=row["url"],
            query=row["query"],
            provider=row["provider"],
            scheduled_at=_parse_dt(row["scheduled_at"]),
            max_results=row["max_results"] if "max_results" in row.keys() else None,
            max_scrape_targets=row["max_scrape_targets"] if "max_scrape_targets" in row.keys() else None,
        )


def build_runner(
    data_dir: str,
    catalog_path: str,
    topics_path: str,
    timeout_seconds: float,
) -> tuple[Settings, ResearchFollowupRunner]:
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(data_dir))
    settings.ensure_dirs()
    store = SQLiteStore(settings.sqlite_path)
    catalog = SourceCatalog.load(catalog_path)
    topics = TopicWatchlists.load(topics_path)
    return settings, ResearchFollowupRunner(settings, store, catalog, topics, timeout_seconds=timeout_seconds)


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _parse_dt(value: str | None):
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except Exception:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed
