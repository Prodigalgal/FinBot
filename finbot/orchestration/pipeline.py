from __future__ import annotations

import hashlib
import json
import os
import traceback
from collections import Counter, defaultdict, deque
from dataclasses import asdict, dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from time import perf_counter
from typing import Any, Callable, Coroutine

from finbot.ai.openai_compatible import DEFAULT_PROVIDER_KEYS_FILE, load_provider_configs
from finbot.config.ai_sites import AI_TASK_ID_COMPRESSION, AISitesConfigStore
from finbot.config.paths import runtime_root
from finbot.config.settings import Settings
from finbot.config.source_catalog import SourceCatalog
from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.dispatcher import Dispatcher
from finbot.ingestion.models import AdapterResult, FetchJob, SourceConfig
from finbot.ingestion.scheduler import SourceScheduler
from finbot.macro.facts import MacroFactBuilder
from finbot.market.confirmation import MarketConfirmationBuilder
from finbot.normalization.evidence_processor import EvidenceProcessor
from finbot.research.ai_compression import AICompressionRunConfig, AICompressionRunner
from finbot.research.briefing import ResearchBriefBuilder
from finbot.research.card_promotion import ResearchCardPromoter
from finbot.research.card_validator import ResearchCardValidator
from finbot.research.context_retriever import DEFAULT_RESEARCH_READINESS, SQLiteResearchContextRetriever
from finbot.research.followup_dispatch import FETCH_JOB_STATUS, ResearchFollowupDispatcher
from finbot.research.followup_runner import ResearchFollowupRunner
from finbot.research.freshness import FreshnessGate
from finbot.research.research_cards import ResearchCardBuildConfig, ResearchCardBuilder
from finbot.research.review_council import ResearchReviewCouncil
from finbot.storage.evidence_store import EvidenceStore
from finbot.storage.sqlite_store import SQLiteStore


DEFAULT_PIPELINE_STEPS = (
    "preflight",
    "ingestion_run",
    "process_evidence",
    "macro_facts",
    "market_confirmation",
    "ai_compression",
    "build_research_cards",
    "validate_research_cards",
    "promote_research_cards",
    "dispatch_followups",
    "run_followups",
    "build_phase4_brief",
    "build_phase41_council",
    "status_snapshot",
)

PIPELINE_TABLES = [
    "sources",
    "source_health",
    "fetch_jobs",
    "fetch_runs",
    "raw_evidence",
    "url_candidates",
    "normalized_documents",
    "dedupe_keys",
    "event_candidates",
    "research_packages",
    "official_release_calendar",
    "market_context_snapshots",
    "market_quotes",
    "market_candles",
    "advisory_reports",
    "paper_order_proposals",
    "macro_release_facts",
    "source_budget_state",
    "ai_compressions",
    "research_cards",
    "research_card_validations",
    "research_card_decisions",
    "research_followup_dispatches",
    "research_watch_items",
    "research_briefs",
    "research_review_verdicts",
    "research_councils",
    "research_pipeline_runs",
    "research_pipeline_steps",
    "research_pipeline_artifacts",
    "autonomous_loop_runs",
    "autonomous_loop_steps",
    "autonomous_loop_artifacts",
    "ai_debate_councils",
    "ai_debate_messages",
    "ai_trade_decisions",
]


@dataclass(frozen=True)
class ResearchPipelineConfig:
    profile: str = "phase5-default"
    triggered_by: str = "cli"
    dry_run: bool = False
    resume_run_id: str | None = None
    from_step: str | None = None
    continue_on_error: bool = False
    clear_existing: bool = False
    idempotent_outputs: bool = True
    catalog_path: str = "config/source_catalog.example.yml"
    topics_path: str = "config/topic_watchlists.example.yml"
    timeout_seconds: float = 25.0
    run_ingestion: bool = False
    force_disabled: bool = False
    source_ids: tuple[str, ...] = ()
    focus_queries: tuple[str, ...] = ()
    max_initial_jobs: int = 30
    max_ingestion_followup_jobs: int = 10
    max_ingestion_followups_per_result: int = 2
    evidence_limit: int | None = None
    max_events: int = 10
    limit_cards: int | None = None
    limit_decisions: int | None = None
    max_dispatch_jobs: int = 50
    run_ai_compression: bool = False
    ai_compression_dry_run: bool = False
    ai_keys_file: str = str(DEFAULT_PROVIDER_KEYS_FILE)
    ai_provider_order: tuple[str, ...] = ("deepseek", "mimo")
    ai_protocol: str = "chat"
    ai_limit_documents: int = 5
    ai_limit_events: int = 3
    run_followups: bool = False
    followups_dry_run: bool = False
    followup_max_jobs: int = 5
    followup_max_discovered_jobs: int = 5
    followup_max_discovered_per_result: int = 1
    rebuild_after_followups: bool = False
    include_watch_only: bool = False
    phase3_time_window: str = "phase3-pipeline"
    phase4_time_window: str = "phase4-pipeline"
    phase41_time_window: str = "phase4.1-pipeline"
    phase4_limit_items: int = 20
    phase41_limit_items: int = 20
    include_background_council: bool = False
    artifact_retention_keep_runs: int | None = None
    artifact_retention_days: int | None = None

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["source_ids"] = list(self.source_ids)
        payload["focus_queries"] = list(self.focus_queries)
        payload["ai_provider_order"] = list(self.ai_provider_order)
        return payload


@dataclass(frozen=True)
class PlannedStep:
    name: str
    enabled: bool
    reason: str
    input: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "enabled": self.enabled,
            "reason": self.reason,
            "input": self.input,
        }


class ResearchPipelineRunner:
    def __init__(
        self,
        settings: Settings,
        store: SQLiteStore,
        catalog: SourceCatalog,
        topics: TopicWatchlists,
    ):
        self.settings = settings
        self.store = store
        self.catalog = catalog
        self.topics = topics

    def plan(self, config: ResearchPipelineConfig) -> list[PlannedStep]:
        return [
            PlannedStep("preflight", True, "always-run", {}),
            PlannedStep(
                "ingestion_run",
                config.run_ingestion,
                "enabled-by-run-ingestion" if config.run_ingestion else "disabled-by-default-network-step",
                {
                    "max_initial_jobs": config.max_initial_jobs,
                    "source_ids": list(config.source_ids),
                    "focus_queries": list(config.focus_queries),
                },
            ),
            PlannedStep("process_evidence", True, "always-run", {"limit": config.evidence_limit}),
            PlannedStep("macro_facts", True, "always-run", {}),
            PlannedStep("market_confirmation", True, "always-run", {"limit_events": config.max_events}),
            PlannedStep(
                "ai_compression",
                config.run_ai_compression,
                "enabled-by-run-ai-compression" if config.run_ai_compression else "disabled-by-default-cost-step",
                {"dry_run": config.ai_compression_dry_run},
            ),
            PlannedStep("build_research_cards", True, "always-run", {"limit_events": config.max_events}),
            PlannedStep("validate_research_cards", True, "always-run", {"limit_cards": config.limit_cards}),
            PlannedStep("promote_research_cards", True, "always-run", {"limit_cards": config.limit_cards}),
            PlannedStep("dispatch_followups", True, "always-run", {"max_jobs": config.max_dispatch_jobs}),
            PlannedStep(
                "run_followups",
                config.run_followups or config.followups_dry_run,
                "enabled-by-run-followups" if config.run_followups else "enabled-by-followups-dry-run" if config.followups_dry_run else "disabled-by-default-network-step",
                {"dry_run": config.followups_dry_run or not config.run_followups},
            ),
            PlannedStep("build_phase4_brief", True, "always-run", {"limit_items": config.phase4_limit_items}),
            PlannedStep("build_phase41_council", True, "always-run", {"limit_items": config.phase41_limit_items}),
            PlannedStep("status_snapshot", True, "always-run", {}),
        ]

    async def run(self, config: ResearchPipelineConfig) -> dict[str, Any]:
        self.store.init_schema()
        planned_steps = self.plan(config)
        start_index = self._start_index(config, planned_steps)
        if config.dry_run:
            return {
                "dry_run": True,
                "profile": config.profile,
                "generated_at": _now(),
                "config": config.to_dict(),
                "resume_run_id": config.resume_run_id,
                "from_step": config.from_step,
                "execution_start_step": planned_steps[start_index].name if planned_steps else None,
                "steps": [step.to_dict() for step in planned_steps],
                "enabled_steps": [step.name for step in planned_steps if step.enabled],
                "disabled_steps": [
                    {"name": step.name, "reason": step.reason}
                    for step in planned_steps
                    if not step.enabled
                ],
            }

        started_at = _now()
        run_id = self._prepare_run(config, planned_steps, started_at)

        step_reports: list[dict[str, Any]] = []
        pipeline_error: str | None = None
        for index, planned_step in enumerate(planned_steps):
            if index < start_index:
                reused = self._reuse_or_skip_prior_step(run_id, planned_step, config)
                step_reports.append(reused)
                continue
            if not planned_step.enabled:
                skipped = self._skipped_step(run_id, planned_step)
                step_reports.append(skipped)
                continue
            report = await self._execute_step(run_id, planned_step, config)
            step_reports.append(report)
            if report["status"] == "failed" and not config.continue_on_error:
                pipeline_error = report["error"]
                break

        finished_at = _now()
        latest_step_reports = self._latest_step_reports(run_id)
        summary = self._run_summary(latest_step_reports)
        status = "failed" if any(step["status"] == "failed" for step in latest_step_reports) else "passed"
        retention_report = self._apply_retention(config)
        if retention_report:
            summary["retention"] = retention_report
        self.store.update_research_pipeline_run(
            run_id=run_id,
            status=status,
            summary=summary,
            finished_at=finished_at,
            error=pipeline_error,
        )
        self.store.insert_research_pipeline_artifact(
            {
                "artifact_id": self._artifact_id(run_id, "run-summary", finished_at),
                "run_id": run_id,
                "step_name": None,
                "artifact_type": "run-summary",
                "ref_id": run_id,
                "payload": summary,
                "created_at": finished_at,
            }
        )
        return {
            "dry_run": False,
            "run_id": run_id,
            "profile": config.profile,
            "status": status,
            "started_at": started_at,
            "finished_at": finished_at,
            "summary": summary,
            "steps": latest_step_reports,
            "current_attempt_steps": step_reports,
            "config": config.to_dict(),
            "error": pipeline_error,
        }

    def _start_index(self, config: ResearchPipelineConfig, planned_steps: list[PlannedStep]) -> int:
        step_names = [step.name for step in planned_steps]
        if config.from_step:
            if config.from_step not in step_names:
                raise ValueError(f"Unknown pipeline step for --from-step: {config.from_step}")
            return step_names.index(config.from_step)
        if config.resume_run_id:
            latest = {row["step_name"]: row["status"] for row in self.store.latest_research_pipeline_steps(config.resume_run_id)}
            for index, step_name in enumerate(step_names):
                if latest.get(step_name) == "failed":
                    return index
        return 0

    def _prepare_run(self, config: ResearchPipelineConfig, planned_steps: list[PlannedStep], started_at: str) -> str:
        summary = {
            "enabled_steps": [step.name for step in planned_steps if step.enabled],
            "from_step": config.from_step,
            "resume_run_id": config.resume_run_id,
        }
        if config.resume_run_id:
            existing = self.store.get_research_pipeline_run(config.resume_run_id)
            if existing is None:
                raise ValueError(f"Cannot resume missing pipeline run: {config.resume_run_id}")
            self.store.update_research_pipeline_run(
                run_id=config.resume_run_id,
                status="running",
                summary={**summary, "resumed_at": started_at},
                finished_at=None,
                error=None,
            )
            return config.resume_run_id

        run_id = self._run_id(config, started_at)
        run_record = {
            "run_id": run_id,
            "profile": config.profile,
            "status": "running",
            "triggered_by": config.triggered_by,
            "config": config.to_dict(),
            "summary": summary,
            "started_at": started_at,
        }
        self.store.insert_research_pipeline_run(run_record)
        return run_id

    def _reuse_or_skip_prior_step(self, run_id: str, planned_step: PlannedStep, config: ResearchPipelineConfig) -> dict[str, Any]:
        previous = self.store.latest_research_pipeline_step(run_id, planned_step.name)
        if previous is None:
            return self._skipped_step(
                run_id,
                PlannedStep(
                    planned_step.name,
                    planned_step.enabled,
                    f"before-from-step:{config.from_step or 'resume-start'}",
                    planned_step.input,
                ),
            )
        if previous["status"] == "failed":
            raise ValueError(
                f"Cannot reuse failed prior step {planned_step.name}; resume from that step or rerun earlier."
            )
        created_at = _now()
        attempt = self.store.next_research_pipeline_step_attempt(run_id, planned_step.name)
        step_id = self._step_id(run_id, planned_step.name, attempt)
        output = {
            "reason": f"reused-before-from-step:{config.from_step or 'resume-start'}",
            "previous_step_id": previous["step_id"],
            "previous_status": previous["status"],
            "previous_attempt": previous["attempt"],
            "previous_output": _loads(previous["output_json"], {}),
        }
        self.store.insert_research_pipeline_step(
            {
                "step_id": step_id,
                "run_id": run_id,
                "step_name": planned_step.name,
                "status": "reused",
                "attempt": attempt,
                "started_at": created_at,
                "finished_at": created_at,
                "duration_ms": 0,
                "input": planned_step.input,
                "output": output,
                "created_at": created_at,
                "updated_at": created_at,
            }
        )
        return {
            "step_id": step_id,
            "step_name": planned_step.name,
            "status": "reused",
            "attempt": attempt,
            "started_at": created_at,
            "finished_at": created_at,
            "duration_ms": 0,
            "input": planned_step.input,
            "output": output,
            "error": None,
        }

    def _latest_step_reports(self, run_id: str) -> list[dict[str, Any]]:
        order = {step_name: index for index, step_name in enumerate(DEFAULT_PIPELINE_STEPS)}
        rows = self.store.latest_research_pipeline_steps(run_id)
        reports = [self._row_to_step_report(row) for row in rows]
        reports.sort(key=lambda report: order.get(report["step_name"], len(order)))
        return reports

    def _row_to_step_report(self, row: Any) -> dict[str, Any]:
        return {
            "step_id": row["step_id"],
            "step_name": row["step_name"],
            "status": row["status"],
            "attempt": row["attempt"],
            "started_at": row["started_at"],
            "finished_at": row["finished_at"],
            "duration_ms": row["duration_ms"],
            "input": _loads(row["input_json"], {}),
            "output": _loads(row["output_json"], {}),
            "error": row["error"],
        }

    def _input_pipeline_run_id(self, table: str, run_id: str) -> str | None:
        return run_id if self.store.has_pipeline_lineage_rows(table, run_id) else None

    def _apply_retention(self, config: ResearchPipelineConfig) -> dict[str, Any] | None:
        if not config.artifact_retention_keep_runs and not config.artifact_retention_days:
            return None
        return self.store.prune_research_pipeline_artifacts(
            keep_runs=config.artifact_retention_keep_runs,
            older_than_days=config.artifact_retention_days,
        )

    async def _execute_step(self, run_id: str, planned_step: PlannedStep, config: ResearchPipelineConfig) -> dict[str, Any]:
        started_at = _now()
        attempt = self.store.next_research_pipeline_step_attempt(run_id, planned_step.name)
        step_id = self._step_id(run_id, planned_step.name, attempt)
        step_record = {
            "step_id": step_id,
            "run_id": run_id,
            "step_name": planned_step.name,
            "status": "running",
            "attempt": attempt,
            "started_at": started_at,
            "input": planned_step.input,
            "output": {},
            "created_at": started_at,
            "updated_at": started_at,
        }
        self.store.insert_research_pipeline_step(step_record)
        start_timer = perf_counter()
        try:
            output = await self._call_step(planned_step.name, config, run_id)
            finished_at = _now()
            duration_ms = int((perf_counter() - start_timer) * 1000)
            compact_output = _compact_output(output)
            self.store.update_research_pipeline_step(
                step_id=step_id,
                status="passed",
                output=compact_output,
                finished_at=finished_at,
                duration_ms=duration_ms,
            )
            self.store.insert_research_pipeline_artifact(
                {
                    "artifact_id": self._artifact_id(run_id, planned_step.name, finished_at),
                    "run_id": run_id,
                    "step_name": planned_step.name,
                    "artifact_type": "step-output",
                    "ref_id": _artifact_ref(output),
                    "payload": compact_output,
                    "created_at": finished_at,
                }
            )
            return {
                "step_id": step_id,
                "step_name": planned_step.name,
                "status": "passed",
                "attempt": attempt,
                "started_at": started_at,
                "finished_at": finished_at,
                "duration_ms": duration_ms,
                "input": planned_step.input,
                "output": compact_output,
                "error": None,
            }
        except Exception as exc:
            finished_at = _now()
            duration_ms = int((perf_counter() - start_timer) * 1000)
            error = f"{type(exc).__name__}: {exc}"
            output = {
                "error": error,
                "traceback": traceback.format_exc(limit=8),
            }
            self.store.update_research_pipeline_step(
                step_id=step_id,
                status="failed",
                output=output,
                finished_at=finished_at,
                duration_ms=duration_ms,
                error=error,
            )
            return {
                "step_id": step_id,
                "step_name": planned_step.name,
                "status": "failed",
                "attempt": attempt,
                "started_at": started_at,
                "finished_at": finished_at,
                "duration_ms": duration_ms,
                "input": planned_step.input,
                "output": output,
                "error": error,
            }

    async def _call_step(self, step_name: str, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        handlers: dict[str, Callable[[ResearchPipelineConfig, str], Coroutine[Any, Any, dict[str, Any]]]] = {
            "preflight": self._step_preflight,
            "ingestion_run": self._step_ingestion_run,
            "process_evidence": self._step_process_evidence,
            "macro_facts": self._step_macro_facts,
            "market_confirmation": self._step_market_confirmation,
            "ai_compression": self._step_ai_compression,
            "build_research_cards": self._step_build_research_cards,
            "validate_research_cards": self._step_validate_research_cards,
            "promote_research_cards": self._step_promote_research_cards,
            "dispatch_followups": self._step_dispatch_followups,
            "run_followups": self._step_run_followups,
            "build_phase4_brief": self._step_build_phase4_brief,
            "build_phase41_council": self._step_build_phase41_council,
            "status_snapshot": self._step_status_snapshot,
        }
        return await handlers[step_name](config, run_id)

    async def _step_preflight(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        for source in self.catalog.sources:
            self.store.upsert_source(source)
        return self._status_snapshot()

    async def _step_ingestion_run(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        settings = self.settings
        scheduler = SourceScheduler(self.topics, focus_queries=config.focus_queries)
        evidence_store = EvidenceStore(settings.evidence_dir)
        dispatcher = Dispatcher(settings, evidence_store, self.topics, timeout_seconds=config.timeout_seconds)
        self.store.prune_catalog_sources({source.id for source in self.catalog.sources})
        source_map = {source.id: source for source in self.catalog.sources}
        selected = set(config.source_ids)
        sources = [source for source in self.catalog.sources if not selected or source.id in selected]
        for source in self.catalog.sources:
            self.store.upsert_source(source)

        queue: deque[tuple[SourceConfig, FetchJob, bool]] = deque()
        for source in sources:
            if not source.enabled and not config.force_disabled:
                continue
            for job in scheduler.jobs_for_source(source):
                queue.append((source, job, False))
                if len(queue) >= config.max_initial_jobs:
                    break
            if len(queue) >= config.max_initial_jobs:
                break

        results: list[AdapterResult] = []
        followups_executed = 0
        try:
            while queue:
                source, job, is_followup = queue.popleft()
                self.store.upsert_fetch_job(job, status="running", detail="pipeline ingestion running")
                result = await dispatcher.dispatch_job(source, job, force_disabled=config.force_disabled)
                results.append(result)
                if result.evidence:
                    self.store.insert_evidence(result.evidence)
                self.store.insert_fetch_run(job, result)
                self.store.upsert_health(result)

                if is_followup:
                    continue
                for next_job in result.discovered_jobs[: config.max_ingestion_followups_per_result]:
                    if followups_executed >= config.max_ingestion_followup_jobs:
                        break
                    next_source = source_map.get(next_job.source_id, source)
                    queue.append((next_source, next_job, True))
                    followups_executed += 1
        finally:
            dispatcher.close()

        return {
            "total_runs": len(results),
            "statuses": dict(Counter(result.status for result in results)),
            "followups_executed": followups_executed,
            "results": [
                {
                    "source_id": result.source_id,
                    "status": result.status,
                    "detail": result.detail,
                    "evidence_id": result.evidence.evidence_id if result.evidence else None,
                    "discovered_jobs": len(result.discovered_jobs),
                }
                for result in results
            ],
        }

    async def _step_process_evidence(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return EvidenceProcessor(self.store).process_all(limit=config.evidence_limit)

    async def _step_macro_facts(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return MacroFactBuilder(self.store).build()

    async def _step_market_confirmation(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return MarketConfirmationBuilder(self.store).build(limit_events=config.max_events)

    async def _step_ai_compression(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        ai_store = AISitesConfigStore(runtime_root(Path.cwd()))
        providers = load_provider_configs(keys_file=Path(config.ai_keys_file), project_root=Path.cwd())
        protocol = config.ai_protocol
        provider_order = config.ai_provider_order
        prompt = None
        if ai_store.exists():
            binding = ai_store.task_binding(AI_TASK_ID_COMPRESSION)
            prompt = ai_store.prompt(AI_TASK_ID_COMPRESSION)
            if binding.enabled:
                protocol = binding.protocol
                provider_order = binding.provider_order() or provider_order
                if binding.site_id and binding.model and binding.site_id in providers:
                    provider = providers[binding.site_id]
                    providers[binding.site_id] = replace(
                        provider,
                        chat_model=binding.model if protocol == "chat" else provider.chat_model,
                        responses_model=binding.model if protocol == "responses" else provider.responses_model,
                    )
        runner = AICompressionRunner(store=self.store, providers=providers)
        return runner.run(
            AICompressionRunConfig(
                pipeline_run_id=run_id,
                protocol=protocol,
                provider_order=provider_order,
                limit_documents=config.ai_limit_documents,
                limit_events=config.ai_limit_events,
                dry_run=config.ai_compression_dry_run,
                clear_existing=config.clear_existing,
                idempotent_outputs=config.idempotent_outputs,
                system_prompt=prompt.system_prompt if prompt else AICompressionRunConfig.system_prompt,
                user_prompt_template=prompt.user_prompt_template if prompt else AICompressionRunConfig.user_prompt_template,
            )
        )

    async def _step_build_research_cards(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        if config.clear_existing:
            self.store.clear_research_cards()
        elif config.idempotent_outputs:
            self.store.clear_research_cards(pipeline_run_id=run_id)
        readiness = DEFAULT_RESEARCH_READINESS
        if config.include_watch_only:
            readiness = (*DEFAULT_RESEARCH_READINESS, "watch-only")
        retriever = SQLiteResearchContextRetriever(store=self.store, freshness_gate=FreshnessGate())
        builder = ResearchCardBuilder()
        events = retriever.candidate_events(limit=config.max_events, readiness=readiness)
        cards = []
        for event in events:
            context = retriever.build_context_pack(event)
            card = builder.build(
                context,
                ResearchCardBuildConfig(
                    time_window=config.phase3_time_window,
                    pipeline_run_id=run_id,
                ),
            )
            self.store.insert_research_card(card)
            cards.append(card)
        return {
            "time_window": config.phase3_time_window,
            "selected_readiness": list(readiness),
            "total_cards": len(cards),
            "readiness": dict(Counter(card["readiness"] for card in cards)),
            "freshness": dict(Counter(card["freshness_status"] for card in cards)),
            "policy_gate": dict(Counter(card["policy_gate"]["status"] for card in cards)),
            "card_ids": [card["card_id"] for card in cards],
        }

    async def _step_validate_research_cards(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return ResearchCardValidator(self.store).validate_all(
            limit=config.limit_cards,
            clear_existing=config.clear_existing,
            pipeline_run_id=run_id,
            input_pipeline_run_id=self._input_pipeline_run_id("research_cards", run_id),
            idempotent_outputs=config.idempotent_outputs,
        )

    async def _step_promote_research_cards(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return ResearchCardPromoter(self.store).promote_all(
            limit=config.limit_cards,
            clear_existing=config.clear_existing,
            pipeline_run_id=run_id,
            cards_pipeline_run_id=self._input_pipeline_run_id("research_cards", run_id),
            validations_pipeline_run_id=self._input_pipeline_run_id("research_card_validations", run_id),
            idempotent_outputs=config.idempotent_outputs,
        )

    async def _step_dispatch_followups(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return ResearchFollowupDispatcher(self.store).dispatch_all(
            limit_decisions=config.limit_decisions,
            max_jobs=config.max_dispatch_jobs,
            clear_existing=config.clear_existing,
            pipeline_run_id=run_id,
            input_pipeline_run_id=self._input_pipeline_run_id("research_card_decisions", run_id),
            idempotent_outputs=config.idempotent_outputs,
        )

    async def _step_run_followups(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        runner = ResearchFollowupRunner(
            settings=self.settings,
            store=self.store,
            catalog=self.catalog,
            topics=self.topics,
            timeout_seconds=config.timeout_seconds,
        )
        dry_run = config.followups_dry_run or not config.run_followups
        try:
            return await runner.run(
                max_jobs=config.followup_max_jobs,
                max_discovered_jobs=config.followup_max_discovered_jobs,
                max_discovered_per_result=config.followup_max_discovered_per_result,
                dry_run=dry_run,
                force_disabled=config.force_disabled,
                rebuild_after_run=config.rebuild_after_followups and not dry_run,
                rebuild_time_window=config.phase3_time_window,
                rebuild_limit_events=config.max_events,
                include_watch_only=config.include_watch_only,
                pipeline_run_id=run_id,
            )
        finally:
            runner.close()

    async def _step_build_phase4_brief(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return ResearchBriefBuilder(self.store).build(
            time_window=config.phase4_time_window,
            limit_items=config.phase4_limit_items,
            clear_existing=config.clear_existing,
            pipeline_run_id=run_id,
            cards_pipeline_run_id=self._input_pipeline_run_id("research_cards", run_id),
            validations_pipeline_run_id=self._input_pipeline_run_id("research_card_validations", run_id),
            decisions_pipeline_run_id=self._input_pipeline_run_id("research_card_decisions", run_id),
            dispatches_pipeline_run_id=self._input_pipeline_run_id("research_followup_dispatches", run_id),
            idempotent_outputs=config.idempotent_outputs,
        )

    async def _step_build_phase41_council(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return ResearchReviewCouncil(self.store).run(
            time_window=config.phase41_time_window,
            limit_items=config.phase41_limit_items,
            clear_existing=config.clear_existing,
            include_background=config.include_background_council,
            pipeline_run_id=run_id,
            brief_pipeline_run_id=self._input_pipeline_run_id("research_briefs", run_id),
            watch_items_pipeline_run_id=self._input_pipeline_run_id("research_watch_items", run_id),
            cards_pipeline_run_id=self._input_pipeline_run_id("research_cards", run_id),
            idempotent_outputs=config.idempotent_outputs,
        )

    async def _step_status_snapshot(self, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        return self._status_snapshot()

    def _status_snapshot(self) -> dict[str, Any]:
        self.store.init_schema()
        with self.store.connect() as conn:
            health_rows = conn.execute("select * from source_health order by source_id").fetchall()
            counts = {
                table: conn.execute(f"select count(*) from {table}").fetchone()[0]
                for table in PIPELINE_TABLES
            }
            budget_rows = conn.execute("select * from source_budget_state order by source_id").fetchall()
        status_counts = Counter(row["status"] for row in health_rows)
        required_keys: dict[str, list[str]] = defaultdict(list)
        blocked_sources: list[dict[str, Any]] = []
        for row in health_rows:
            keys = _loads(row["required_keys_json"], [])
            for key in keys:
                required_keys[key].append(row["source_id"])
            if row["status"] in {"blocked-by-credential", "blocked-by-provider", "disabled-by-scope", "failed"}:
                blocked_sources.append(
                    {
                        "source_id": row["source_id"],
                        "status": row["status"],
                        "detail": row["detail"],
                        "required_keys": keys,
                    }
                )
        active_budget_blocks = [
            {
                "source_id": row["source_id"],
                "status": row["status"],
                "throttled_until": row["throttled_until"],
                "last_error": row["last_error"],
            }
            for row in budget_rows
            if row["status"] in {"budget-exhausted", "throttled"}
        ]
        return {
            "generated_at": _now(),
            "counts": counts,
            "source_statuses": dict(status_counts),
            "required_keys": dict(required_keys),
            "blocked_sources": blocked_sources,
            "active_budget_blocks": active_budget_blocks,
            "policy": {
                "research_only": True,
                "no_trading_api": True,
                "firecrawl_requires_proxy_pool": True,
            },
        }

    def _skipped_step(self, run_id: str, planned_step: PlannedStep) -> dict[str, Any]:
        created_at = _now()
        attempt = self.store.next_research_pipeline_step_attempt(run_id, planned_step.name)
        step_id = self._step_id(run_id, planned_step.name, attempt)
        output = {"reason": planned_step.reason}
        self.store.insert_research_pipeline_step(
            {
                "step_id": step_id,
                "run_id": run_id,
                "step_name": planned_step.name,
                "status": "skipped",
                "attempt": attempt,
                "started_at": created_at,
                "finished_at": created_at,
                "duration_ms": 0,
                "input": planned_step.input,
                "output": output,
                "created_at": created_at,
                "updated_at": created_at,
            }
        )
        return {
            "step_id": step_id,
            "step_name": planned_step.name,
            "status": "skipped",
            "attempt": attempt,
            "started_at": created_at,
            "finished_at": created_at,
            "duration_ms": 0,
            "input": planned_step.input,
            "output": output,
            "error": None,
        }

    def _run_summary(self, steps: list[dict[str, Any]]) -> dict[str, Any]:
        statuses = Counter(step["status"] for step in steps)
        failed_steps = [step["step_name"] for step in steps if step["status"] == "failed"]
        passed_steps = [step["step_name"] for step in steps if step["status"] == "passed"]
        skipped_steps = [step["step_name"] for step in steps if step["status"] == "skipped"]
        return {
            "statuses": dict(statuses),
            "passed_steps": passed_steps,
            "skipped_steps": skipped_steps,
            "failed_steps": failed_steps,
            "latest_attempts": {step["step_name"]: step.get("attempt") for step in steps},
            "total_duration_ms": sum(int(step.get("duration_ms") or 0) for step in steps),
        }

    def _run_id(self, config: ResearchPipelineConfig, started_at: str) -> str:
        value = json.dumps(config.to_dict(), ensure_ascii=False, sort_keys=True, default=str)
        return hashlib.sha256(f"research-pipeline:{started_at}:{value}".encode("utf-8")).hexdigest()[:32]

    def _step_id(self, run_id: str, step_name: str, attempt: int) -> str:
        return hashlib.sha256(f"{run_id}:{step_name}:{attempt}".encode("utf-8")).hexdigest()

    def _artifact_id(self, run_id: str, artifact_type: str, created_at: str) -> str:
        return hashlib.sha256(f"{run_id}:{artifact_type}:{created_at}".encode("utf-8")).hexdigest()


def build_pipeline_runner(
    data_dir: str,
    catalog_path: str,
    topics_path: str,
) -> tuple[Settings, ResearchPipelineRunner]:
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(data_dir))
    settings.ensure_dirs()
    store = SQLiteStore(settings.sqlite_path)
    store.init_schema()
    catalog = SourceCatalog.load(catalog_path)
    topics = TopicWatchlists.load(topics_path)
    return settings, ResearchPipelineRunner(settings=settings, store=store, catalog=catalog, topics=topics)


def provider_order_from_env() -> tuple[str, ...]:
    raw_value = os.getenv("AI_COMPRESSION_PROVIDER_ORDER", "deepseek,mimo")
    values = tuple(value.strip() for value in raw_value.split(",") if value.strip())
    filtered = tuple(value for value in values if value in {"deepseek", "mimo"})
    return filtered or ("deepseek", "mimo")


def _compact_output(output: dict[str, Any]) -> dict[str, Any]:
    compact = dict(output)
    for key in ("cards", "items", "validations", "results", "facts", "snapshots"):
        value = compact.get(key)
        if isinstance(value, list):
            compact[key] = {
                "count": len(value),
                "sample": [_compact_item(item) for item in value[:3]],
            }
    if isinstance(compact.get("brief"), dict):
        brief = compact["brief"]
        compact["brief"] = {
            "brief_id": brief.get("brief_id"),
            "time_window": brief.get("time_window"),
            "created_at": brief.get("created_at"),
            "summary": brief.get("summary"),
            "watch_items_count": len(brief.get("watch_items") or []),
            "operator_actions_count": len(brief.get("operator_actions") or []),
            "policy_gate": brief.get("policy_gate"),
        }
    if isinstance(compact.get("council"), dict):
        council = compact["council"]
        compact["council"] = {
            "council_id": council.get("council_id"),
            "brief_id": council.get("brief_id"),
            "time_window": council.get("time_window"),
            "status": council.get("status"),
            "summary": council.get("summary"),
            "items_count": len(council.get("items") or []),
            "policy_gate": council.get("policy_gate"),
        }
    return compact


def _compact_item(item: Any) -> Any:
    if not isinstance(item, dict):
        return item
    preferred_keys = (
        "id",
        "card_id",
        "event_id",
        "event_key",
        "source_id",
        "job_id",
        "status",
        "decision",
        "priority",
        "score",
        "fact_id",
        "provider",
        "release_type",
        "snapshot_id",
        "created_at",
        "error",
        "detail",
    )
    compact = {key: item[key] for key in preferred_keys if key in item}
    for key in ("headline", "title", "summary", "rationale"):
        if key in item:
            compact[key] = str(item[key])[:240]
    if not compact:
        for key, value in list(item.items())[:8]:
            if isinstance(value, (dict, list)):
                compact[key] = f"{type(value).__name__}[{len(value)}]"
            else:
                compact[key] = value
    return compact


def _artifact_ref(output: dict[str, Any]) -> str | None:
    for key in ("brief_id", "council_id", "run_id"):
        if output.get(key):
            return str(output[key])
    return None


def _loads(value: str | None, default: Any = None) -> Any:
    if default is None:
        default = []
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
