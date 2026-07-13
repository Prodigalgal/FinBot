from __future__ import annotations

import asyncio
import hashlib
import json
import threading
import traceback
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from fastapi import BackgroundTasks, FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field

from finbot.advisory.engine import AdvisoryConfig
from finbot.ai.openai_compatible import DEFAULT_PROVIDER_KEYS_FILE, OpenAICompatibleClient, OpenAICompatibleProvider
from finbot.autonomous import (
    INSTANT_RESEARCH_TRIGGER,
    AutonomousLoopConfig,
    AutonomousLoopScheduler,
    AutonomousRequestQueue,
    AutonomousResearchLoopRunner,
)
from finbot.cli.common import build_store, write_report
from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.paths import runtime_root
from finbot.config.runtime_config import CONFIG_FIELD_MAP, PROXY_POLICY_KEYS, RuntimeConfigStore, normalize_value
from finbot.council.director import WORKFLOW_DEPTH_POLICIES
from finbot.council.learning import CouncilLearningService
from finbot.council.models import (
    COUNCIL_WORKFLOW_LATEST_VERSION,
    SUPPORTED_ACTIVATION_MODES,
    SUPPORTED_CONDITION_OPERATORS,
    SUPPORTED_CONTEXT_MODES,
    SUPPORTED_EDGE_CONTEXT_MODES,
    SUPPORTED_REASONING_EFFORTS,
    SUPPORTED_SCHEDULING_MODES,
    SUPPORTED_WORKFLOW_NODE_TYPES,
)
from finbot.council.operations import WorkflowOperationRegistry
from finbot.council.runtime import WorkflowRunService
from finbot.config.setup_profiles import (
    SETUP_PROFILE_VERSION,
    apply_setup_profile as apply_runtime_setup_profile,
    setup_default_summary,
    setup_profile_catalog,
    setup_readiness,
)
from finbot.exchange.account_snapshot import resolve_pnl_window
from finbot.exchange.runtime import execute_paper_decisions, fetch_exchange_accounts
from finbot.instruments.product_center import ProductCatalogService
from finbot.market.public_exchanges import PublicExchangeMarketDataClient
from finbot.network.proxy_policy import load_proxy_policy
from finbot.network.proxy_runtime import ProxyRuntime
from finbot.operator.workbench import OperatorWorkbenchBuilder, OperatorWorkbenchConfig, parse_intervals, parse_provider_specs
from finbot.orchestration.pipeline import PIPELINE_TABLES, ResearchPipelineConfig, build_pipeline_runner, provider_order_from_env
from finbot.storage.sqlite_store import StaleRecordError
from finbot.workspace.history import ResearchHistoryService
from finbot.workspace.feedback import ResearchFeedbackService
from finbot.workspace.reviews import DecisionReviewService
from finbot.workspace.workflow_versions import WorkflowVersionService
from finbot.web.auth import (
    AuthenticationMiddleware,
    AuthManager,
    AuthSettings,
    LoginChallengeError,
    LoginRequest,
)
from finbot.web.health import HealthService
from finbot.web.quant_api import quant_router
from finbot.web.sse import SSE_HEADERS, snapshot_event_stream


REPORT_FILES = {
    "autonomous-loop": "autonomous-loop-latest.json",
    "autonomous-status": "autonomous-status-latest.json",
    "autonomous-recommendations": "autonomous-loop-latest.json",
    "operator-workbench": "operator-workbench-latest.json",
    "research-pipeline": "research-pipeline-latest.json",
    "ingestion-status": "ingestion-status.json",
    "recommendation-evaluation": "recommendation-evaluation-latest.json",
    "portfolio-risk": "portfolio-risk-latest.json",
    "ai-governance": "ai-governance-latest.json",
    "paper-execution": "paper-execution-latest.json",
}


class WebJobRequest(BaseModel):
    model_config = ConfigDict(extra="allow")


class ResearchPipelineJobRequest(WebJobRequest):
    dry_run: bool = True
    run_ingestion: bool = False
    run_ai_compression: bool = False
    run_followups: bool = False
    followups_dry_run: bool = True
    profile: str = "phase7-web"


class OperatorWorkbenchJobRequest(WebJobRequest):
    symbols: list[str] = Field(default_factory=lambda: ["BTCUSDT"])
    providers: list[str] = Field(default_factory=lambda: ["gate"])
    intervals: list[str] = Field(default_factory=lambda: ["1h", "4h", "1d"])
    candle_limit: int = 5
    start_bridges: bool = True
    persist: bool = True
    profile: str = "phase7-web"


class ProxyDiagnosticsJobRequest(WebJobRequest):
    start_bridges: bool = False


class SystemConfigUpdateRequest(BaseModel):
    values: dict[str, Any] = Field(default_factory=dict)
    clear_keys: list[str] = Field(default_factory=list)


class AIConfigUpdateRequest(BaseModel):
    sites: list[dict[str, Any]] = Field(default_factory=list)
    task_bindings: dict[str, Any] = Field(default_factory=dict)
    prompts: dict[str, Any] = Field(default_factory=dict)
    council_templates: list[dict[str, Any]] = Field(default_factory=list)
    experiments: list[dict[str, Any]] = Field(default_factory=list)


class AIModelRefreshRequest(BaseModel):
    site_id: str
    protocol: str = "chat"


class SetupProfileApplyRequest(BaseModel):
    profile_id: str = "recommended"
    preserve_existing: bool = True


class AutonomousRunNowRequest(WebJobRequest):
    trigger_type: str = "manual-api"


class InstantResearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    symbols: list[str] = Field(default_factory=list, max_length=12)
    product_id: str | None = Field(default=None, max_length=120)
    preferred_instrument_id: str | None = Field(default=None, max_length=120)
    watchlist_id: str | None = Field(default=None, max_length=120)
    provider: str | None = Field(default=None, max_length=40)
    market_type: str | None = Field(default=None, max_length=40)


class DecisionReviewUpdateRequest(BaseModel):
    status: str
    note: str = Field(default="", max_length=2000)
    expected_version: int | None = Field(default=None, ge=0)


class ReviewedPaperExecutionRequest(BaseModel):
    adapter_ids: list[str] = Field(default_factory=list, max_length=2)
    confirm_simulated_execution: bool = False


class HistoryResumeRequest(BaseModel):
    from_step: str | None = Field(default=None, max_length=120)


class WorkflowDraftRequest(BaseModel):
    template: dict[str, Any]
    workflow_version_id: str | None = Field(default=None, max_length=100)
    parent_version_id: str | None = Field(default=None, max_length=100)
    expected_checksum: str | None = Field(default=None, max_length=100)
    change_note: str = Field(default="", max_length=500)


class WorkflowRollbackRequest(BaseModel):
    publish: bool = True


class WorkflowEstimateRequest(BaseModel):
    template: dict[str, Any]
    rounds: int = Field(default=3, ge=1, le=10)


class WorkflowNodeTestRequest(BaseModel):
    template: dict[str, Any]
    node_id: str = Field(min_length=2, max_length=100)
    workflow_version_id: str | None = Field(default=None, max_length=100)
    sample_input: dict[str, Any] = Field(default_factory=dict)


class WorkflowDirectorRequest(WebJobRequest):
    trigger_type: str = Field(default="manual", max_length=80)
    query: str | None = Field(default=None, max_length=500)
    template_id: str | None = Field(default=None, max_length=100)
    depth: str | None = Field(default=None, max_length=20)
    rounds: int | None = Field(default=None, ge=1, le=32)
    product_id: str | None = Field(default=None, max_length=120)
    symbol: str | None = Field(default=None, max_length=80)
    product_type: str | None = Field(default=None, max_length=80)
    market_type: str | None = Field(default=None, max_length=80)
    evidence_status: str | None = Field(default=None, max_length=80)


class WorkflowRunRequest(WorkflowDirectorRequest):
    dry_run: bool = True
    workflow_version_id: str | None = Field(default=None, max_length=100)


class WorkflowResumeRequest(BaseModel):
    node_outputs: dict[str, dict[str, Any]] = Field(default_factory=dict)


class CouncilMemoryRetrieveRequest(BaseModel):
    template_id: str = Field(min_length=2, max_length=100)
    role_id: str | None = Field(default=None, max_length=100)
    product_id: str | None = Field(default=None, max_length=120)
    symbol: str | None = Field(default=None, max_length=80)
    market_type: str | None = Field(default=None, max_length=80)
    topics: list[str] = Field(default_factory=list, max_length=20)
    limit: int = Field(default=5, ge=1, le=20)
    max_chars: int = Field(default=6_000, ge=500, le=20_000)


class NotificationStatusRequest(BaseModel):
    status: str


class WatchlistCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    description: str = Field(default="", max_length=500)


class WatchlistUpdateRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=80)
    description: str | None = Field(default=None, max_length=500)


class WatchlistItemUpdateRequest(BaseModel):
    preferred_instrument_id: str | None = Field(default=None, max_length=80)
    research_mode: str = "monitor"
    notes: str = Field(default="", max_length=2000)
    tags: list[str] = Field(default_factory=list, max_length=20)
    alert_policy: dict[str, Any] = Field(default_factory=dict)
    sort_order: int = Field(default=0, ge=-100_000, le=100_000)


@dataclass
class JobRecord:
    job_id: str
    kind: str
    status: str
    request: dict[str, Any]
    created_at: str
    started_at: str | None = None
    finished_at: str | None = None
    result: dict[str, Any] | None = None
    error: str | None = None
    traceback: str | None = None

    def to_dict(self, include_traceback: bool = False) -> dict[str, Any]:
        payload = {
            "job_id": self.job_id,
            "kind": self.kind,
            "status": self.status,
            "request": self.request,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "result": self.result,
            "error": self.error,
        }
        if include_traceback:
            payload["traceback"] = self.traceback
        return payload


class JobManager:
    def __init__(self, max_jobs: int = 100):
        self.max_jobs = max_jobs
        self._jobs: dict[str, JobRecord] = {}
        self._order: list[str] = []
        self._lock = threading.Lock()

    def submit(self, kind: str, request: dict[str, Any], fn: Callable[[dict[str, Any]], dict[str, Any]]) -> JobRecord:
        created_at = _now()
        job_id = _job_id(kind, request, created_at)
        record = JobRecord(job_id=job_id, kind=kind, status="queued", request=request, created_at=created_at)
        with self._lock:
            self._jobs[job_id] = record
            self._order.insert(0, job_id)
            self._trim_locked()
        thread = threading.Thread(target=self._run, args=(job_id, fn), name=f"finbot-web-{kind}", daemon=True)
        thread.start()
        return record

    def list_jobs(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            job_ids = self._order[: max(1, min(limit, self.max_jobs))]
            return [self._jobs[job_id].to_dict() for job_id in job_ids if job_id in self._jobs]

    def get(self, job_id: str) -> JobRecord | None:
        with self._lock:
            return self._jobs.get(job_id)

    def run_pending(self, job_id: str, fn: Callable[[dict[str, Any]], dict[str, Any]]) -> None:
        self._run(job_id, fn)

    def _run(self, job_id: str, fn: Callable[[dict[str, Any]], dict[str, Any]]) -> None:
        with self._lock:
            record = self._jobs[job_id]
            record.status = "running"
            record.started_at = _now()
        try:
            result = fn(record.request)
            with self._lock:
                record.status = "succeeded"
                record.result = result
                record.finished_at = _now()
        except Exception as exc:
            with self._lock:
                record.status = "failed"
                record.error = f"{type(exc).__name__}: {exc}"
                record.traceback = traceback.format_exc()
                record.finished_at = _now()

    def _trim_locked(self) -> None:
        while len(self._order) > self.max_jobs:
            old_id = self._order.pop()
            self._jobs.pop(old_id, None)


@dataclass
class FinBotWebApp:
    data_dir: str = "data"
    catalog_path: str = "config/source_catalog.example.yml"
    topics_path: str = "config/topic_watchlists.example.yml"
    jobs: JobManager = field(default_factory=JobManager)
    config_store: RuntimeConfigStore | None = None
    ai_config_store: AISitesConfigStore | None = None
    autonomous_scheduler: AutonomousLoopScheduler | None = None
    auth_manager: AuthManager | None = None
    runtime_store: Any | None = None

    def __post_init__(self) -> None:
        if self.config_store is None:
            self.config_store = RuntimeConfigStore(runtime_root(Path.cwd()))
        if self.ai_config_store is None:
            self.ai_config_store = AISitesConfigStore(runtime_root(Path.cwd()))
        if self.runtime_store is None:
            self.runtime_store = build_store(self.autonomous_config().data_dir)[1]
        if self.autonomous_scheduler is None and bool(self.config_store.value("worker.embedded_scheduler", False)):
            self.autonomous_scheduler = AutonomousLoopScheduler(
                config_loader=self.autonomous_config,
                runner_factory=lambda _config: AutonomousResearchLoopRunner(),
            )

    def autonomous_config(self) -> AutonomousLoopConfig:
        return AutonomousLoopConfig.from_runtime_config(
            self.config_store,
            data_dir=self.data_dir,
            catalog_path=self.catalog_path,
            topics_path=self.topics_path,
        )

    def start_autonomous_scheduler(self) -> None:
        if self.autonomous_scheduler is not None:
            self.autonomous_scheduler.start()

    def stop_autonomous_scheduler(self) -> None:
        if self.autonomous_scheduler is not None:
            self.autonomous_scheduler.stop()

    def autonomous_store(self) -> Any:
        return self.runtime_store

    def decision_review_service(self) -> DecisionReviewService:
        return DecisionReviewService(self.autonomous_store())

    def research_history_service(self) -> ResearchHistoryService:
        return ResearchHistoryService(self.autonomous_store())

    def workflow_version_service(self) -> WorkflowVersionService:
        return WorkflowVersionService(self.autonomous_store(), self.ai_config_store)

    def workflow_run_service(self) -> WorkflowRunService:
        return WorkflowRunService(self.autonomous_store(), self.ai_config_store)

    def council_learning_service(self) -> CouncilLearningService:
        return CouncilLearningService(self.autonomous_store())

    def research_feedback_service(self) -> ResearchFeedbackService:
        return ResearchFeedbackService(self.autonomous_store())

    def autonomous_worker_snapshot(self) -> dict[str, Any]:
        snapshot = AutonomousRequestQueue(self.autonomous_store()).snapshot(request_limit=20)
        stale_after_seconds = max(
            15.0,
            float(self.config_store.value("worker.heartbeat_seconds", 5.0)) * 3,
        )
        for worker in snapshot["workers"]:
            worker["active"] = (
                worker.get("status") not in {"stopped", "error"}
                and _recent_timestamp(worker.get("heartbeat_at"), stale_after_seconds)
            )
        return snapshot

    def autonomous_scheduler_snapshot(self) -> dict[str, Any]:
        if self.autonomous_scheduler is not None:
            return {"mode": "embedded", **self.autonomous_scheduler.snapshot()}
        config = self.autonomous_config()
        worker = self.autonomous_worker_snapshot()
        active_workers = [
            row
            for row in worker["workers"]
            if row.get("active") is True
        ]
        return {
            "mode": "worker",
            "status": "running" if active_workers else "offline",
            "enabled": config.enabled,
            "interval_minutes": config.interval_minutes,
            "running": any(row.get("current_request_id") for row in active_workers),
            "next_run_at": (worker.get("scheduler") or {}).get("next_run_at"),
            "active_worker_count": len(active_workers),
            "queue": worker["queue"],
        }

    def config_payload(self) -> dict[str, Any]:
        settings, _ = build_store(self.data_dir)
        policy_values = _proxy_policy_values(settings.proxy_policy_file)
        payload = self.config_store.snapshot(settings, proxy_policy_values=policy_values)
        payload["proxy_policy_path"] = str(settings.proxy_policy_file) if settings.proxy_policy_file else None
        payload["hot_reload"] = {
            "status": "enabled",
            "scope": "配置保存后，下一次状态查询、报告读取、代理诊断或新后台任务会读取最新值；正在运行中的任务不被中途改写。",
        }
        return payload

    def update_config(self, values: dict[str, Any], clear_keys: list[str]) -> dict[str, Any]:
        settings, _ = build_store(self.data_dir)
        runtime_updates = {key: value for key, value in values.items() if key not in PROXY_POLICY_KEYS}
        runtime_clear = [key for key in clear_keys if key not in PROXY_POLICY_KEYS]
        proxy_updates = {key: value for key, value in values.items() if key in PROXY_POLICY_KEYS}
        proxy_clear = [key for key in clear_keys if key in PROXY_POLICY_KEYS]
        if runtime_updates or runtime_clear:
            self.config_store.update(runtime_updates, runtime_clear)
        if proxy_updates or proxy_clear:
            _update_proxy_policy(settings.proxy_policy_file, proxy_updates, proxy_clear)
        return self.config_payload()

    def setup_payload(self, application: dict[str, Any] | None = None) -> dict[str, Any]:
        ai_payload = self.ai_config_payload()
        scheduler_snapshot = self.autonomous_scheduler_snapshot()
        payload = {
            "status": "ok",
            "version": SETUP_PROFILE_VERSION,
            "profiles": setup_profile_catalog(self.config_store),
            "readiness": setup_readiness(
                self.config_store,
                ai_payload=ai_payload,
                scheduler_snapshot=scheduler_snapshot,
            ),
            "defaults": setup_default_summary(self.config_store, ai_payload),
        }
        if application is not None:
            payload["application"] = application
        return payload

    def apply_setup_profile(self, profile_id: str, preserve_existing: bool) -> dict[str, Any]:
        application = apply_runtime_setup_profile(
            self.config_store,
            profile_id=profile_id,
            preserve_existing=preserve_existing,
        )
        return self.setup_payload(application=application)

    def ai_config_payload(self) -> dict[str, Any]:
        keys_file = Path(str(_config_value(self, "ai.keys_file", DEFAULT_PROVIDER_KEYS_FILE)))
        payload = self.ai_config_store.public_payload(keys_file=keys_file)
        payload["workflow_schema"] = self.workflow_schema_payload()
        learning = self.council_learning_service().snapshot(limit=20)
        payload["learning_summary"] = {
            "role_scores": learning["role_scores"],
            "memory_count": learning["memory_count"],
            "policy": learning["policy"],
        }
        return payload

    def workflow_schema_payload(self) -> dict[str, Any]:
        templates = self.ai_config_store.council_templates()
        return {
            "status": "ok",
            "latest_version": COUNCIL_WORKFLOW_LATEST_VERSION,
            "supported_versions": [1, COUNCIL_WORKFLOW_LATEST_VERSION],
            "node_types": sorted(SUPPORTED_WORKFLOW_NODE_TYPES),
            "operations": sorted(WorkflowOperationRegistry.SUPPORTED_OPERATIONS),
            "condition_operators": sorted(SUPPORTED_CONDITION_OPERATORS),
            "activation_modes": sorted(SUPPORTED_ACTIVATION_MODES),
            "context_modes": sorted(SUPPORTED_CONTEXT_MODES),
            "edge_context_modes": sorted(SUPPORTED_EDGE_CONTEXT_MODES),
            "scheduling_modes": sorted(SUPPORTED_SCHEDULING_MODES),
            "reasoning_efforts": ["provider_default", "none", "minimal", "low", "medium", "high", "xhigh"],
            "depth_policies": WORKFLOW_DEPTH_POLICIES,
            "templates": [
                {
                    "template_id": template.template_id,
                    "display_name": template.display_name,
                    "description": template.description,
                    "builtin": template.builtin,
                    "template_kind": template.template_kind,
                    "recommended_for": list(template.recommended_for),
                    "cost_tier": template.cost_tier,
                    "workflow_version": template.workflow.version,
                    "role_count": len(template.enabled_roles()),
                    "node_count": len(template.workflow.nodes),
                    "default_rounds": template.round_policy.default_rounds,
                }
                for template in templates
            ],
            "credential_policy": {
                "node_reference_field": "site_id",
                "raw_key_in_workflow_allowed": False,
                "raw_key_in_run_log_allowed": False,
            },
            "safety": {
                "arbitrary_code_allowed": False,
                "arbitrary_http_allowed": False,
                "trading_execution_allowed": False,
                "hidden_reasoning_exposed": False,
            },
        }

    def update_ai_config(self, payload: dict[str, Any]) -> dict[str, Any]:
        self.ai_config_store.update(payload)
        return self.ai_config_payload()

    def refresh_ai_models(self, site_id: str, protocol: str) -> dict[str, Any]:
        protocol = protocol.strip().lower()
        if protocol not in {"chat", "responses"}:
            raise ValueError(f"AI 协议不支持：{protocol}")
        keys_file = Path(str(_config_value(self, "ai.keys_file", DEFAULT_PROVIDER_KEYS_FILE)))
        sites = self.ai_config_store.sites(keys_file=keys_file)
        site = sites.get(site_id)
        if site is None:
            raise ValueError(f"未找到已启用 AI 站点或站点缺少配置：{site_id}")
        provider = OpenAICompatibleProvider(
            name=site.site_id,
            api_key=site.api_key,
            base_url=site.base_url,
            chat_model=site.default_chat_model,
            responses_model=site.default_responses_model,
            timeout_seconds=site.timeout_seconds,
        )
        models = OpenAICompatibleClient().list_models(provider)
        self.ai_config_store.update_models(site_id=site_id, protocol=protocol, models=models)
        return {
            "status": "ok",
            "site_id": site_id,
            "protocol": protocol,
            "models": models,
            "config": self.ai_config_payload(),
        }

    def status_payload(self) -> dict[str, Any]:
        settings, store = build_store(self.data_dir)
        latest_pipeline = None
        latest_advisory = None
        latest_autonomous = None
        with store.connect() as conn:
            counts = {
                table: conn.execute(f"select count(*) from {table}").fetchone()[0]
                for table in PIPELINE_TABLES
            }
            for table in (
                "recommendation_evaluation_runs",
                "recommendation_outcomes",
                "portfolio_risk_reports",
                "ai_prompt_versions",
                "ai_invocations",
                "claim_evidence_audits",
                "ai_governance_reports",
            ):
                counts[table] = conn.execute(f"select count(*) from {table}").fetchone()[0]
            source_status_rows = conn.execute(
                "select status, count(*) as count from source_health group by status order by status"
            ).fetchall()
            latest_pipeline_run = conn.execute(
                "select * from research_pipeline_runs order by started_at desc limit 1"
            ).fetchone()
            if latest_pipeline_run:
                steps = conn.execute(
                    """
                    select steps.step_name, steps.status, steps.attempt, steps.duration_ms, steps.error
                    from research_pipeline_steps steps
                    join (
                      select step_name, max(attempt) as max_attempt
                      from research_pipeline_steps
                      where run_id = ?
                      group by step_name
                    ) latest
                      on steps.step_name = latest.step_name
                     and steps.attempt = latest.max_attempt
                    where steps.run_id = ?
                    order by steps.created_at, steps.step_name
                    """,
                    (latest_pipeline_run["run_id"], latest_pipeline_run["run_id"]),
                ).fetchall()
                latest_pipeline = {
                    "run_id": latest_pipeline_run["run_id"],
                    "profile": latest_pipeline_run["profile"],
                    "status": latest_pipeline_run["status"],
                    "started_at": latest_pipeline_run["started_at"],
                    "finished_at": latest_pipeline_run["finished_at"],
                    "error": latest_pipeline_run["error"],
                    "summary": _loads(latest_pipeline_run["summary_json"], {}),
                    "steps": [
                        {
                            "step_name": row["step_name"],
                            "status": row["status"],
                            "attempt": row["attempt"],
                            "duration_ms": row["duration_ms"],
                            "error": row["error"],
                        }
                        for row in steps
                    ],
                }
            latest_advisory_row = conn.execute(
                "select * from advisory_reports order by generated_at desc limit 1"
            ).fetchone()
            if latest_advisory_row:
                latest_advisory = {
                    "report_id": latest_advisory_row["report_id"],
                    "profile": latest_advisory_row["profile"],
                    "status": latest_advisory_row["status"],
                    "generated_at": latest_advisory_row["generated_at"],
                    "summary": (_loads(latest_advisory_row["payload_json"], {}) or {}).get("summary", {}),
                }
            latest_autonomous_row = conn.execute(
                "select * from autonomous_loop_runs order by started_at desc limit 1"
            ).fetchone()
            if latest_autonomous_row:
                latest_autonomous = _autonomous_run_payload(store, latest_autonomous_row, include_steps=True)
        return {
            "status": "ok",
            "service": "finbot-web",
            "generated_at": _now(),
            "data_dir": str(settings.data_dir),
            "reports_dir": str(settings.reports_dir),
            "runtime_config_path": str(self.config_store.path),
            "proxy_policy_path": str(settings.proxy_policy_file) if settings.proxy_policy_file else None,
            "counts": counts,
            "source_statuses": {row["status"]: row["count"] for row in source_status_rows},
            "autonomous_scheduler": self.autonomous_scheduler_snapshot(),
            "latest_autonomous_loop": latest_autonomous,
            "latest_pipeline_run": latest_pipeline,
            "latest_advisory_report": latest_advisory,
            "jobs": self.jobs.list_jobs(limit=10),
            "policy": {
                "execution_allowed": False,
                "private_exchange_api_allowed": False,
                "order_endpoints_allowed": False,
                "firecrawl_direct_allowed": False,
            },
        }

    def autonomous_status_payload(self) -> dict[str, Any]:
        settings, store = build_store(self.autonomous_config().data_dir)
        runs = [
            _autonomous_run_payload(store, row, include_steps=index == 0)
            for index, row in enumerate(store.list_autonomous_loop_runs(limit=10))
        ]
        latest_report = self.latest_report("autonomous-loop")
        recommendations = latest_report.get("recommended_products", []) if isinstance(latest_report, dict) else []
        report_loop_run_id = latest_report.get("loop_run_id") if isinstance(latest_report, dict) else None
        fallback_result_run = next(
            (
                run
                for run in runs
                if run.get("finished_at") and run.get("status") not in {"running", "abandoned"}
            ),
            None,
        )
        latest_result_loop_run_id = (
            str(report_loop_run_id)
            if isinstance(report_loop_run_id, str) and report_loop_run_id.strip()
            else fallback_result_run.get("loop_run_id") if fallback_result_run else None
        )
        latest_decision_readiness = (
            latest_report.get("decision_readiness")
            if isinstance(latest_report.get("decision_readiness"), dict)
            else (fallback_result_run or {}).get("decision_readiness")
        )
        latest_debates = [
            _ai_debate_payload(row, store=store)
            for row in store.list_ai_debate_councils(limit=3, loop_run_id=latest_result_loop_run_id)
        ]
        latest_decisions = [
            _ai_trade_decision_payload(row)
            for row in store.list_ai_trade_decisions(limit=10, loop_run_id=latest_result_loop_run_id)
        ]
        return {
            "status": "ok",
            "generated_at": _now(),
            "data_dir": str(settings.data_dir),
            "scheduler": self.autonomous_scheduler_snapshot(),
            "worker": self.autonomous_worker_snapshot(),
            "config": self.autonomous_config().to_dict(),
            "recent_runs": runs,
            "latest_result_loop_run_id": latest_result_loop_run_id,
            "latest_decision_readiness": latest_decision_readiness,
            "latest_recommendations": recommendations if isinstance(recommendations, list) else [],
            "latest_ai_debates": latest_debates,
            "latest_ai_decisions": latest_decisions,
            "latest_universe": store.latest_universe(),
            "latest_evaluation": self.latest_report("recommendation-evaluation"),
            "latest_portfolio_risk": self.latest_report("portfolio-risk"),
            "latest_ai_governance": self.latest_report("ai-governance"),
            "paper_execution": self.paper_execution_status_payload(),
            "policy": {
                "execution_allowed": False,
                "order_api_allowed": False,
                "private_exchange_api_allowed": False,
                "simulated_execution_allowed": True,
                "human_confirmation_required": self.autonomous_config().paper_execution_require_human_review,
            },
        }

    def operations_stream_payload(self) -> dict[str, Any]:
        return {
            "status": self.status_payload(),
            "autonomous": self.autonomous_status_payload(),
            "jobs": self.jobs.list_jobs(limit=50),
        }

    def paper_execution_status_payload(self) -> dict[str, Any]:
        config = self.autonomous_config()
        store = self.autonomous_store()
        enabled_adapters = set(config.paper_execution_adapters)
        adapter_secrets = {
            "gate_testnet": (config.gate_testnet_api_key, config.gate_testnet_api_secret),
            "bybit_demo": (config.bybit_demo_api_key, config.bybit_demo_api_secret),
        }
        probe_path = Path(self.data_dir) / "reports" / "paper-exchange-credential-probe.json"
        probe_payload = _loads(probe_path.read_text(encoding="utf-8"), {}) if probe_path.exists() else {}
        adapters = []
        for adapter_id, display_name, provider, environment in (
            ("gate_testnet", "Gate TestNet", "gate", "testnet"),
            ("bybit_demo", "Bybit Demo", "bybit", "demo"),
        ):
            api_key, api_secret = adapter_secrets[adapter_id]
            credentials_configured = bool(api_key and api_secret)
            credential_probe = _credential_probe_summary(
                adapter_id=adapter_id,
                probe_payload=probe_payload,
                credential_fingerprint=_credential_fingerprint(api_key, api_secret),
            )
            enabled = adapter_id in enabled_adapters
            blockers: list[str] = []
            if enabled and not credentials_configured:
                blockers.append("缺少模拟环境 API key/secret")
            if enabled and credentials_configured and credential_probe["status"] == "failed":
                blockers.append(str(credential_probe["reason"]))
            if (
                enabled
                and config.paper_execution_submit_orders
                and credentials_configured
                and credential_probe["status"] == "unverified"
            ):
                blockers.append("模拟环境凭据尚未通过只读鉴权")
            if not enabled:
                adapter_status = "disabled"
            elif blockers:
                adapter_status = "blocked"
            elif credential_probe["status"] == "passed":
                adapter_status = "ready"
            elif not config.paper_execution_submit_orders:
                adapter_status = "dry_run"
            else:
                adapter_status = "unverified"
            adapters.append(
                {
                    "adapter_id": adapter_id,
                    "display_name": display_name,
                    "provider": provider,
                    "environment": environment,
                    "enabled": enabled,
                    "credentials_configured": credentials_configured,
                    "credentials_verified": (
                        True
                        if credential_probe["status"] == "passed"
                        else False
                        if credential_probe["status"] == "failed"
                        else None
                    ),
                    "status": adapter_status,
                    "dry_run_ready": enabled,
                    "blockers": blockers,
                    "credential_probe": credential_probe,
                }
            )
        runs = [_paper_execution_run_payload(row) for row in store.list_paper_execution_runs(limit=10)]
        executions = [_paper_execution_payload(row) for row in store.list_paper_executions(limit=50)]
        return {
            "status": "ok",
            "generated_at": _now(),
            "enabled": config.paper_execution_enabled,
            "mode": "submit" if config.paper_execution_submit_orders else "dry_run",
            "adapters": adapters,
            "policy": {
                "max_orders_per_adapter": config.paper_execution_max_orders_per_adapter,
                "max_notional_usdt": config.paper_execution_max_notional_usdt,
                "min_confidence": config.paper_execution_min_confidence,
                "require_human_review": config.paper_execution_require_human_review,
                "real_trading_allowed": False,
                "mainnet_private_api_allowed": False,
            },
            "recent_runs": runs,
            "recent_executions": executions,
        }

    def exchange_accounts_payload(
        self,
        *,
        pnl_range: str = "all",
        start_at: str | None = None,
        end_at: str | None = None,
    ) -> dict[str, Any]:
        pnl_window = resolve_pnl_window(
            pnl_range,
            start_at=start_at,
            end_at=end_at,
        )
        return fetch_exchange_accounts(
            config=self.autonomous_config(),
            pnl_window=pnl_window,
        )

    def instrument_catalog_payload(self, limit: int = 200) -> dict[str, Any]:
        store = self.autonomous_store()
        rows = [dict(row) for row in store.list_venue_instruments(active_only=False)[: max(1, min(limit, 1000))]]
        for row in rows:
            row["active"] = bool(row["active"])
            row["contract"] = bool(row["contract"])
            row["linear"] = None if row["linear"] is None else bool(row["linear"])
            row["inverse"] = None if row["inverse"] is None else bool(row["inverse"])
            row["leverage"] = _loads(row.pop("leverage_json", "{}"), {})
            row.pop("payload_json", None)
        return {"status": "ok", "count": len(rows), "instruments": rows}

    def trigger_autonomous_now(self, trigger_type: str = "manual-api") -> dict[str, Any]:
        if self.autonomous_scheduler is not None:
            return self.autonomous_scheduler.trigger_now(trigger_type=trigger_type)
        request = AutonomousRequestQueue(self.autonomous_store()).enqueue(
            trigger_type=trigger_type,
            payload={"requested_by": "web-api"},
        )
        return {"status": "accepted", "mode": "worker", "request": request}

    def trigger_instant_research(
        self,
        query: str,
        symbols: list[str],
        product_context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        normalized_query = " ".join(query.split()).strip()
        if len(normalized_query) < 2:
            raise ValueError("即时研究问题至少需要 2 个字符")
        normalized_symbols = [str(symbol).strip().upper() for symbol in symbols if str(symbol).strip()][:12]
        request = AutonomousRequestQueue(self.autonomous_store()).enqueue(
            trigger_type=INSTANT_RESEARCH_TRIGGER,
            payload={
                "mode": INSTANT_RESEARCH_TRIGGER,
                "query": normalized_query,
                "focus_queries": _instant_focus_queries(normalized_query),
                "symbols": normalized_symbols,
                "product_context": _instant_product_context(product_context),
                "requested_by": "web-api",
            },
        )
        return {
            "status": "accepted",
            "mode": "worker",
            "session": self.instant_research_payload(str(request["request_id"])),
        }

    def list_instant_research(self, limit: int = 20) -> dict[str, Any]:
        store = self.autonomous_store()
        rows = [
            row
            for row in store.list_autonomous_requests(limit=max(100, min(limit * 10, 500)))
            if row["trigger_type"] == INSTANT_RESEARCH_TRIGGER
        ][: max(1, min(limit, 50))]
        sessions = [_instant_research_summary_payload(store, row) for row in rows]
        return {"status": "ok", "count": len(sessions), "sessions": sessions}

    def instant_research_payload(self, request_id: str) -> dict[str, Any]:
        store = self.autonomous_store()
        row = store.get_autonomous_request(request_id)
        if row is None or row["trigger_type"] != INSTANT_RESEARCH_TRIGGER:
            raise LookupError(f"未找到即时研究会话：{request_id}")
        request = _autonomous_request_payload(row)
        loop_run = None
        research_pipeline = None
        debate = None
        decisions: list[dict[str, Any]] = []
        recommendations: list[dict[str, Any]] = []
        loop_run_id = request.get("loop_run_id")
        if loop_run_id:
            loop_row = store.get_autonomous_loop_run(str(loop_run_id))
            if loop_row is not None:
                full_loop_run = _autonomous_run_payload(store, loop_row, include_steps=True)
                research_pipeline = _instant_research_pipeline_payload(store, str(loop_run_id))
                debate_rows = store.list_ai_debate_councils(limit=1, loop_run_id=str(loop_run_id))
                debate = _ai_debate_payload(debate_rows[0], store=store) if debate_rows else None
                decisions = [
                    _ai_trade_decision_payload(decision)
                    for decision in store.list_ai_trade_decisions(limit=20, loop_run_id=str(loop_run_id))
                ]
                recommendations = _instant_recommendations(full_loop_run, decisions)
                loop_run = _instant_loop_run_payload(full_loop_run)
        progress = _instant_progress(request, loop_run)
        return {
            "session_id": request_id,
            "query": str((request.get("payload") or {}).get("query") or ""),
            "product_context": (request.get("payload") or {}).get("product_context") or {},
            "status": request["status"],
            "stage": _instant_stage(request, loop_run),
            "requested_at": request["requested_at"],
            "started_at": request.get("started_at"),
            "finished_at": request.get("finished_at"),
            "error": request.get("error"),
            "loop_run_id": loop_run_id,
            "progress": progress,
            "loop_run": loop_run,
            "research_pipeline": research_pipeline,
            "debate": debate,
            "decisions": decisions,
            "recommendations": recommendations,
            "policy": {
                "execution_allowed": False,
                "paper_execution_allowed": False,
                "hidden_chain_of_thought_exposed": False,
            },
        }

    def execute_reviewed_decision(
        self,
        decision_id: str,
        *,
        adapter_ids: list[str],
        confirm_simulated_execution: bool,
    ) -> dict[str, Any]:
        config = self.autonomous_config()
        if not config.paper_execution_enabled:
            raise ValueError("模拟执行步骤尚未启用")
        if config.paper_execution_submit_orders and not confirm_simulated_execution:
            raise ValueError("提交模拟订单需要显式确认")
        requested_adapters = tuple(dict.fromkeys(str(value).strip() for value in adapter_ids if str(value).strip()))
        enabled_adapters = set(config.paper_execution_adapters)
        if requested_adapters and any(adapter_id not in enabled_adapters for adapter_id in requested_adapters):
            raise ValueError("请求包含未启用的模拟交易 adapter")
        context = self.decision_review_service().execution_context(decision_id)
        return execute_paper_decisions(
            config=config,
            loop_run_id=context["loop_run_id"],
            decisions=[context["decision"]],
            portfolio_risk=context["portfolio_risk"],
            ai_governance=context["ai_governance"],
            adapter_ids=requested_adapters or None,
        )

    def latest_report(self, kind: str) -> dict[str, Any]:
        settings, _ = build_store(self.data_dir)
        filename = REPORT_FILES.get(kind)
        if not filename:
            raise ValueError(f"不支持的报告类型：{kind}")
        path = settings.reports_dir / filename
        if not path.exists():
            return {"status": "empty", "kind": kind, "path": str(path)}
        return _loads(path.read_text(encoding="utf-8"), {})

    def submit_research_pipeline(self, request: dict[str, Any], background_tasks: BackgroundTasks) -> JobRecord:
        return self._submit("research-pipeline", request, self.run_research_pipeline, background_tasks)

    def submit_operator_workbench(self, request: dict[str, Any], background_tasks: BackgroundTasks) -> JobRecord:
        return self._submit("operator-workbench", request, self.run_operator_workbench, background_tasks)

    def submit_proxy_diagnostics(self, request: dict[str, Any], background_tasks: BackgroundTasks) -> JobRecord:
        return self._submit("proxy-diagnostics", request, self.run_proxy_diagnostics, background_tasks)

    def _submit(
        self,
        kind: str,
        request: dict[str, Any],
        fn: Callable[[dict[str, Any]], dict[str, Any]],
        background_tasks: BackgroundTasks,
    ) -> JobRecord:
        created_at = _now()
        job_id = _job_id(kind, request, created_at)
        record = JobRecord(job_id=job_id, kind=kind, status="queued", request=request, created_at=created_at)
        with self.jobs._lock:
            self.jobs._jobs[job_id] = record
            self.jobs._order.insert(0, job_id)
            self.jobs._trim_locked()
        background_tasks.add_task(self.jobs.run_pending, job_id, fn)
        return record

    def run_research_pipeline(self, request: dict[str, Any]) -> dict[str, Any]:
        catalog_path = str(_request_value(self, request, "catalog_path", "system.catalog_path", self.catalog_path))
        topics_path = str(_request_value(self, request, "topics_path", "system.topics_path", self.topics_path))
        settings, runner = build_pipeline_runner(
            data_dir=self.data_dir,
            catalog_path=catalog_path,
            topics_path=topics_path,
        )
        provider_order = tuple(
            _list_value(request.get("ai_provider_order"))
            or _list_value(_config_value(self, "ai.provider_order", provider_order_from_env()))
        )
        config = ResearchPipelineConfig(
            profile=str(request.get("profile") or "phase7-web"),
            triggered_by="web",
            dry_run=_bool_value(_request_value(self, request, "dry_run", "research.dry_run", True), True),
            resume_run_id=_optional_str(request.get("resume_run_id")),
            from_step=_optional_str(request.get("from_step")),
            continue_on_error=_bool_value(request.get("continue_on_error"), False),
            clear_existing=_bool_value(request.get("clear_existing"), False),
            idempotent_outputs=_bool_value(request.get("idempotent_outputs"), True),
            catalog_path=catalog_path,
            topics_path=topics_path,
            timeout_seconds=_float_value(request.get("timeout_seconds"), 25.0, minimum=1.0, maximum=120.0),
            run_ingestion=_bool_value(_request_value(self, request, "run_ingestion", "research.run_ingestion", False), False),
            force_disabled=_bool_value(request.get("force_disabled"), False),
            source_ids=tuple(_list_value(request.get("source_ids"))),
            max_initial_jobs=_int_value(request.get("max_initial_jobs"), 30, minimum=1, maximum=200),
            max_ingestion_followup_jobs=_int_value(request.get("max_ingestion_followup_jobs"), 10, minimum=0, maximum=100),
            max_ingestion_followups_per_result=_int_value(request.get("max_ingestion_followups_per_result"), 2, minimum=0, maximum=20),
            evidence_limit=_optional_int(_request_value(self, request, "evidence_limit", "research.evidence_limit", None), minimum=1, maximum=5000),
            max_events=_int_value(_request_value(self, request, "max_events", "research.max_events", 10), 10, minimum=1, maximum=200),
            limit_cards=_optional_int(_request_value(self, request, "limit_cards", "research.limit_cards", None), minimum=1, maximum=1000),
            limit_decisions=_optional_int(request.get("limit_decisions"), minimum=1, maximum=1000),
            max_dispatch_jobs=_int_value(request.get("max_dispatch_jobs"), 50, minimum=0, maximum=500),
            run_ai_compression=_bool_value(_request_value(self, request, "run_ai_compression", "research.run_ai_compression", False), False),
            ai_compression_dry_run=_bool_value(_request_value(self, request, "ai_compression_dry_run", "research.ai_compression_dry_run", True), True),
            ai_keys_file=str(_request_value(self, request, "ai_keys_file", "ai.keys_file", ResearchPipelineConfig.ai_keys_file)),
            ai_provider_order=provider_order,
            ai_protocol=str(_request_value(self, request, "ai_protocol", "ai.protocol", "chat") or "chat"),
            ai_limit_documents=_int_value(request.get("ai_limit_documents"), 5, minimum=0, maximum=100),
            ai_limit_events=_int_value(request.get("ai_limit_events"), 3, minimum=0, maximum=100),
            run_followups=_bool_value(_request_value(self, request, "run_followups", "research.run_followups", False), False),
            followups_dry_run=_bool_value(_request_value(self, request, "followups_dry_run", "research.followups_dry_run", True), True),
            followup_max_jobs=_int_value(request.get("followup_max_jobs"), 5, minimum=0, maximum=100),
            followup_max_discovered_jobs=_int_value(request.get("followup_max_discovered_jobs"), 5, minimum=0, maximum=100),
            followup_max_discovered_per_result=_int_value(request.get("followup_max_discovered_per_result"), 1, minimum=0, maximum=20),
            rebuild_after_followups=_bool_value(request.get("rebuild_after_followups"), False),
            include_watch_only=_bool_value(request.get("include_watch_only"), False),
            phase3_time_window=str(request.get("phase3_time_window") or "phase3-web"),
            phase4_time_window=str(request.get("phase4_time_window") or "phase4-web"),
            phase41_time_window=str(request.get("phase41_time_window") or "phase4.1-web"),
            phase4_limit_items=_int_value(request.get("phase4_limit_items"), 20, minimum=1, maximum=200),
            phase41_limit_items=_int_value(request.get("phase41_limit_items"), 20, minimum=1, maximum=200),
            include_background_council=_bool_value(request.get("include_background_council"), False),
            artifact_retention_keep_runs=_optional_int(request.get("artifact_retention_keep_runs"), minimum=1, maximum=1000),
            artifact_retention_days=_optional_int(request.get("artifact_retention_days"), minimum=1, maximum=3650),
        )
        report = asyncio.run(runner.run(config))
        output = write_report(settings, "research-pipeline-latest.json", report)
        return {
            "status": report.get("status", "dry-run") if not report.get("dry_run") else "dry-run",
            "dry_run": report.get("dry_run"),
            "run_id": report.get("run_id"),
            "summary": report.get("summary"),
            "enabled_steps": report.get("enabled_steps"),
            "disabled_steps": report.get("disabled_steps"),
            "steps": _compact_steps(report.get("steps") or report.get("current_attempt_steps") or []),
            "output": str(output),
        }

    def run_operator_workbench(self, request: dict[str, Any]) -> dict[str, Any]:
        settings, store = build_store(self.data_dir)
        proxy_runtime = ProxyRuntime.from_settings(
            settings,
            start_bridges=_bool_value(_request_value(self, request, "start_bridges", "operator.start_bridges", True), True),
        )
        try:
            market_client = PublicExchangeMarketDataClient(
                timeout_seconds=_float_value(request.get("timeout_seconds"), 20.0, minimum=1.0, maximum=120.0),
                user_agent=settings.http_user_agent,
                proxy_router=proxy_runtime.router,
            )
            builder = OperatorWorkbenchBuilder(store=store, market_client=market_client)
            default_providers = _config_value(
                self,
                "operator.providers",
                _config_value(self, "exchange.enabled_public_providers", ("gate",)),
            )
            config = OperatorWorkbenchConfig(
                symbols=tuple(_list_value(_request_value(self, request, "symbols", "operator.symbols", OperatorWorkbenchConfig.symbols)) or OperatorWorkbenchConfig.symbols),
                providers=parse_provider_specs(tuple(_list_value(_request_value(self, request, "providers", "operator.providers", default_providers)))),
                data_source="live_public",
                execution_mode="advisory_only",
                intervals=parse_intervals(tuple(_list_value(_request_value(self, request, "intervals", "operator.intervals", OperatorWorkbenchConfig.intervals)))),
                candle_limit=_int_value(_request_value(self, request, "candle_limit", "operator.candle_limit", 5), 5, minimum=2, maximum=500),
                timeout_seconds=_float_value(request.get("timeout_seconds"), 20.0, minimum=1.0, maximum=120.0),
                persist=_bool_value(_request_value(self, request, "persist", "operator.persist", True), True),
                include_research_context=_bool_value(_request_value(self, request, "include_research_context", "operator.include_research_context", True), True),
                advisory=AdvisoryConfig(
                    profile=str(request.get("profile") or "phase7-web"),
                    risk_per_trade_pct=_float_value(_request_value(self, request, "risk_per_trade_pct", "operator.risk_per_trade_pct", 0.5), 0.5, minimum=0.0, maximum=10.0),
                    max_position_notional_pct=_float_value(_request_value(self, request, "max_position_notional_pct", "operator.max_position_notional_pct", 5.0), 5.0, minimum=0.0, maximum=100.0),
                    reward_risk_ratio=_float_value(_request_value(self, request, "reward_risk_ratio", "operator.reward_risk_ratio", 1.6), 1.6, minimum=0.1, maximum=20.0),
                ),
            )
            report = asyncio.run(builder.build(config))
            report["proxy_runtime"] = proxy_runtime.summary()
            output = write_report(settings, "operator-workbench-latest.json", report)
            return {
                "status": report["status"],
                "report_id": report["report_id"],
                "summary": report["summary"],
                "policy": report["policy"],
                "rate_observation_count": len(report.get("rate_limit", {}).get("observations", [])),
                "proxy_policy": report.get("proxy_runtime", {}).get("proxy_policy"),
                "output": str(output),
            }
        finally:
            proxy_runtime.close()

    def run_proxy_diagnostics(self, request: dict[str, Any]) -> dict[str, Any]:
        settings, _ = build_store(self.data_dir)
        runtime = ProxyRuntime.from_settings(settings, start_bridges=_bool_value(request.get("start_bridges"), False))
        try:
            targets = request.get("targets")
            if not isinstance(targets, dict) or not targets:
                targets = {
                    "firecrawl": "https://api.firecrawl.dev/v2/search",
                    "exchange:binance": "https://data-api.binance.vision/api/v3/time",
                    "exchange:bybit": "https://api.bybit.com/v5/market/time",
                    "exchange:gate": "https://api.gateio.ws/api/v4/spot/time",
                }
            rows = []
            for route, url in targets.items():
                decision = runtime.router.decide(str(route), str(url))
                rows.append({"route": route, "url": url, "decision": decision.to_dict()})
            return {"status": "ok", "runtime": runtime.summary(), "targets": rows}
        finally:
            runtime.close()


def create_fastapi_app(
    state: FinBotWebApp | None = None,
    frontend_dist: str | None = None,
    auth_settings: AuthSettings | None = None,
) -> FastAPI:
    app_state = state or FinBotWebApp()
    auth_manager = AuthManager(auth_settings or AuthSettings.from_env())
    app_state.auth_manager = auth_manager
    health_service = HealthService(app_state)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        app_state.start_autonomous_scheduler()
        try:
            yield
        finally:
            app_state.stop_autonomous_scheduler()

    app = FastAPI(
        title="FinBot 网页服务",
        version="0.1.0",
        description="FinBot 研究流水线、交易建议工作台、代理诊断和自动研究闭环的统一 API 服务。",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(auth_manager.settings.trusted_origins) if auth_manager.settings.enabled else ["*"],
        allow_credentials=auth_manager.settings.enabled,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(AuthenticationMiddleware, manager=auth_manager)
    app.state.finbot = app_state
    app.state.auth_manager = auth_manager
    app.include_router(quant_router(app_state.autonomous_store))

    def product_service() -> ProductCatalogService:
        return ProductCatalogService(app_state.autonomous_store())

    def product_call(method: Callable[..., dict[str, Any]], *args: Any, **kwargs: Any) -> dict[str, Any]:
        try:
            return method(*args, **kwargs)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return health_service.liveness()

    @app.get("/health/live")
    async def health_live() -> dict[str, Any]:
        return health_service.liveness()

    @app.get("/health/ready")
    async def health_ready() -> JSONResponse:
        status_code, payload = health_service.readiness()
        return JSONResponse(status_code=status_code, content=payload)

    @app.get("/api/v1/auth/status")
    async def auth_status(request: Request) -> dict[str, Any]:
        session = auth_manager.session_for_request(request)
        return {
            "status": "ok",
            "enabled": auth_manager.settings.enabled,
            "account_model": "single_admin",
            "challenge_required": auth_manager.settings.enabled,
            "authenticated": session is not None,
            "session": session.to_dict() if session is not None else None,
        }

    @app.get("/api/v1/auth/challenge")
    async def auth_challenge(request: Request) -> dict[str, Any]:
        identity = request.client.host if request.client else "unknown"
        return {"status": "ok", **auth_manager.issue_login_challenge(identity)}

    @app.post("/api/v1/auth/login")
    async def auth_login(request: Request, credentials: LoginRequest, response: Response) -> dict[str, Any]:
        identity = request.client.host if request.client else "unknown"
        try:
            session = auth_manager.authenticate(
                credentials.username,
                credentials.password,
                identity,
                challenge_id=credentials.challenge_id,
                math_answer=credentials.math_answer,
                pow_nonce=credentials.pow_nonce,
            )
        except PermissionError as exc:
            raise HTTPException(status_code=429, detail=str(exc)) from exc
        except LoginChallengeError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        if session is None:
            raise HTTPException(status_code=401, detail="用户名或密码错误")
        auth_manager.issue_cookie(response, session)
        return {"status": "ok", "authenticated": True, "session": session.to_dict()}

    @app.post("/api/v1/auth/logout")
    async def auth_logout(response: Response) -> dict[str, Any]:
        auth_manager.clear_cookie(response)
        return {"status": "ok", "authenticated": False}

    @app.get("/api/v1/status")
    async def status() -> dict[str, Any]:
        return app_state.status_payload()

    @app.get("/api/v1/autonomous/status")
    async def autonomous_status() -> dict[str, Any]:
        return app_state.autonomous_status_payload()

    @app.get("/api/v1/stream/operations")
    async def operations_stream(request: Request) -> StreamingResponse:
        return StreamingResponse(
            snapshot_event_stream(
                request,
                app_state.operations_stream_payload,
                event_name="snapshot",
                poll_seconds=6.0,
            ),
            media_type="text/event-stream",
            headers=SSE_HEADERS,
        )

    @app.get("/api/v1/paper-execution/status")
    async def paper_execution_status() -> dict[str, Any]:
        return app_state.paper_execution_status_payload()

    @app.get("/api/v1/exchange-accounts")
    def exchange_accounts(
        pnl_range: str = "all",
        start_at: str | None = None,
        end_at: str | None = None,
    ) -> dict[str, Any]:
        try:
            return app_state.exchange_accounts_payload(
                pnl_range=pnl_range,
                start_at=start_at,
                end_at=end_at,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/instruments")
    async def instruments(limit: int = 200) -> dict[str, Any]:
        return app_state.instrument_catalog_payload(limit=limit)

    @app.get("/api/v1/products")
    async def products(
        search: str | None = None,
        provider: str | None = None,
        market_type: str | None = None,
        product_type: str | None = None,
        active_only: bool = True,
        watchlist_id: str | None = None,
        watched_only: bool = False,
        page: int = 1,
        page_size: int = 25,
    ) -> dict[str, Any]:
        service = product_service()
        return product_call(
            service.list_products,
            search=search,
            provider=provider,
            market_type=market_type,
            product_type=product_type,
            active_only=active_only,
            watchlist_id=watchlist_id,
            watched_only=watched_only,
            page=page,
            page_size=page_size,
        )

    @app.get("/api/v1/products/{product_id}")
    async def product_detail(product_id: str, watchlist_id: str | None = None) -> dict[str, Any]:
        service = product_service()
        return product_call(service.get_product, product_id, watchlist_id)

    @app.get("/api/v1/watchlists")
    async def watchlists() -> dict[str, Any]:
        return product_service().list_watchlists()

    @app.post("/api/v1/watchlists", status_code=201)
    async def create_watchlist(request: WatchlistCreateRequest) -> dict[str, Any]:
        service = product_service()
        return product_call(service.create_watchlist, name=request.name, description=request.description)

    @app.get("/api/v1/watchlists/{watchlist_id}")
    async def watchlist_detail(watchlist_id: str) -> dict[str, Any]:
        service = product_service()
        return product_call(service.get_watchlist, watchlist_id)

    @app.patch("/api/v1/watchlists/{watchlist_id}")
    async def update_watchlist(watchlist_id: str, request: WatchlistUpdateRequest) -> dict[str, Any]:
        service = product_service()
        return product_call(
            service.update_watchlist,
            watchlist_id,
            request.model_dump(exclude_unset=True),
        )

    @app.delete("/api/v1/watchlists/{watchlist_id}")
    async def delete_watchlist(watchlist_id: str) -> dict[str, Any]:
        service = product_service()
        return product_call(service.delete_watchlist, watchlist_id)

    @app.put("/api/v1/watchlists/{watchlist_id}/items/{product_id}")
    async def save_watchlist_item(
        watchlist_id: str,
        product_id: str,
        request: WatchlistItemUpdateRequest,
    ) -> dict[str, Any]:
        service = product_service()
        return product_call(
            service.upsert_watchlist_item,
            watchlist_id,
            product_id,
            **request.model_dump(),
        )

    @app.delete("/api/v1/watchlists/{watchlist_id}/items/{product_id}")
    async def delete_watchlist_item(watchlist_id: str, product_id: str) -> dict[str, Any]:
        service = product_service()
        return product_call(service.delete_watchlist_item, watchlist_id, product_id)

    @app.get("/api/v1/universe/latest")
    async def latest_universe() -> dict[str, Any]:
        return app_state.autonomous_store().latest_universe() or {"status": "empty", "instruments": []}

    @app.post("/api/v1/autonomous/run-now", status_code=202)
    async def autonomous_run_now(request: AutonomousRunNowRequest) -> dict[str, Any]:
        try:
            return app_state.trigger_autonomous_now(request.trigger_type)
        except RuntimeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/instant-research", status_code=202)
    async def submit_instant_research(request: InstantResearchRequest) -> dict[str, Any]:
        try:
            return app_state.trigger_instant_research(
                request.query,
                request.symbols,
                {
                    "product_id": request.product_id,
                    "preferred_instrument_id": request.preferred_instrument_id,
                    "watchlist_id": request.watchlist_id,
                    "provider": request.provider,
                    "market_type": request.market_type,
                },
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/instant-research")
    async def instant_research_sessions(limit: int = 20) -> dict[str, Any]:
        return app_state.list_instant_research(limit=limit)

    @app.get("/api/v1/instant-research/{request_id}")
    async def instant_research_detail(request_id: str) -> dict[str, Any]:
        try:
            return app_state.instant_research_payload(request_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get("/api/v1/instant-research/{request_id}/events")
    async def instant_research_events(request_id: str, request: Request) -> StreamingResponse:
        try:
            app_state.instant_research_payload(request_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        return StreamingResponse(
            snapshot_event_stream(
                request,
                lambda: app_state.instant_research_payload(request_id),
                event_name="session",
                poll_seconds=2.0,
                terminal_statuses=frozenset({"succeeded", "partial", "failed", "cancelled"}),
            ),
            media_type="text/event-stream",
            headers=SSE_HEADERS,
        )

    @app.get("/api/v1/decision-reviews")
    async def decision_reviews(status: str | None = None, limit: int = 100) -> dict[str, Any]:
        try:
            return app_state.decision_review_service().list_inbox(status=status, limit=limit)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/decision-reviews/{decision_id}")
    async def decision_review_detail(decision_id: str) -> dict[str, Any]:
        try:
            return app_state.decision_review_service().get_review(decision_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.put("/api/v1/decision-reviews/{decision_id}")
    async def update_decision_review(decision_id: str, request: DecisionReviewUpdateRequest) -> dict[str, Any]:
        try:
            return app_state.decision_review_service().review_decision(
                decision_id,
                status=request.status,
                note=request.note,
                expected_version=request.expected_version,
            )
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except StaleRecordError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/decision-reviews/{decision_id}/paper-execution")
    async def execute_reviewed_decision(
        decision_id: str,
        request: ReviewedPaperExecutionRequest,
    ) -> dict[str, Any]:
        try:
            return app_state.execute_reviewed_decision(
                decision_id,
                adapter_ids=request.adapter_ids,
                confirm_simulated_execution=request.confirm_simulated_execution,
            )
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/history/runs")
    async def history_runs(limit: int = 50, status: str | None = None) -> dict[str, Any]:
        return app_state.research_history_service().list_runs(limit=limit, status=status)

    @app.get("/api/v1/history/runs/compare")
    async def compare_history_runs(left: str, right: str) -> dict[str, Any]:
        try:
            return app_state.research_history_service().compare_runs(left, right)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/history/runs/{loop_run_id}")
    async def history_run_detail(loop_run_id: str) -> dict[str, Any]:
        try:
            return app_state.research_history_service().get_run(loop_run_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/api/v1/history/runs/{loop_run_id}/replay", status_code=202)
    async def replay_history_run(loop_run_id: str) -> dict[str, Any]:
        try:
            return app_state.research_history_service().replay_run(loop_run_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/history/runs/{loop_run_id}/resume", status_code=202)
    async def resume_history_run(
        loop_run_id: str,
        request: HistoryResumeRequest,
        background_tasks: BackgroundTasks,
    ) -> dict[str, Any]:
        try:
            resume_request = app_state.research_history_service().resume_request(loop_run_id, request.from_step)
            job = app_state.submit_research_pipeline(resume_request, background_tasks)
            return {"status": "accepted", "resume": resume_request, "job": job.to_dict()}
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/workflows/versions")
    async def workflow_versions(template_id: str | None = None) -> dict[str, Any]:
        try:
            return app_state.workflow_version_service().list_versions(template_id)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/workflows/schema")
    async def workflow_schema() -> dict[str, Any]:
        return app_state.workflow_schema_payload()

    @app.post("/api/v1/workflows/director/plan")
    async def plan_workflow(request: WorkflowDirectorRequest) -> dict[str, Any]:
        try:
            return app_state.workflow_run_service().plan(request.model_dump(exclude_none=True))
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/workflows/runs")
    async def workflow_runs(
        template_id: str | None = None,
        status: str | None = None,
        limit: int = 100,
    ) -> dict[str, Any]:
        return app_state.workflow_run_service().list(
            template_id=template_id,
            status=status,
            limit=limit,
        )

    @app.post("/api/v1/workflows/runs")
    async def run_workflow(request: WorkflowRunRequest) -> dict[str, Any]:
        payload = request.model_dump(exclude_none=True)
        dry_run = bool(payload.pop("dry_run", True))
        workflow_version_id = payload.pop("workflow_version_id", None)
        try:
            return app_state.workflow_run_service().run(
                payload,
                dry_run=dry_run,
                workflow_version_id=workflow_version_id,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/workflows/runs/{workflow_run_id}")
    async def workflow_run_detail(workflow_run_id: str) -> dict[str, Any]:
        try:
            return app_state.workflow_run_service().get(workflow_run_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/api/v1/workflows/runs/{workflow_run_id}/resume")
    async def resume_workflow_run(
        workflow_run_id: str,
        request: WorkflowResumeRequest,
    ) -> dict[str, Any]:
        try:
            return app_state.workflow_run_service().resume(
                workflow_run_id,
                node_outputs=request.node_outputs,
            )
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except (ValueError, StaleRecordError) as exc:
            raise HTTPException(status_code=409 if isinstance(exc, StaleRecordError) else 400, detail=str(exc)) from exc

    @app.get("/api/v1/workflows/learning")
    async def workflow_learning(template_id: str | None = None, limit: int = 100) -> dict[str, Any]:
        return app_state.council_learning_service().snapshot(
            template_id=template_id,
            limit=max(1, min(limit, 500)),
        )

    @app.post("/api/v1/workflows/learning/debates/{debate_id}/refresh")
    async def refresh_workflow_learning(debate_id: str) -> dict[str, Any]:
        try:
            return app_state.council_learning_service().refresh_debate(debate_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.post("/api/v1/workflows/learning/retrieve")
    async def retrieve_workflow_memory(request: CouncilMemoryRetrieveRequest) -> dict[str, Any]:
        return app_state.council_learning_service().retrieve(**request.model_dump())

    @app.post("/api/v1/workflows/drafts")
    async def save_workflow_draft(request: WorkflowDraftRequest) -> dict[str, Any]:
        try:
            return app_state.workflow_version_service().save_draft(
                request.template,
                workflow_version_id=request.workflow_version_id,
                parent_version_id=request.parent_version_id,
                expected_checksum=request.expected_checksum,
                change_note=request.change_note,
            )
        except StaleRecordError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/workflows/versions/{workflow_version_id}/publish")
    async def publish_workflow_version(workflow_version_id: str) -> dict[str, Any]:
        try:
            return app_state.workflow_version_service().publish(workflow_version_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/workflows/versions/{workflow_version_id}/rollback")
    async def rollback_workflow_version(
        workflow_version_id: str,
        request: WorkflowRollbackRequest,
    ) -> dict[str, Any]:
        try:
            return app_state.workflow_version_service().rollback(workflow_version_id, publish=request.publish)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/workflows/estimate")
    async def estimate_workflow(request: WorkflowEstimateRequest) -> dict[str, Any]:
        try:
            return app_state.workflow_version_service().estimate(request.template, request.rounds)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/workflows/node-test")
    async def test_workflow_node(request: WorkflowNodeTestRequest) -> dict[str, Any]:
        try:
            return app_state.workflow_version_service().test_node(
                request.template,
                node_id=request.node_id,
                workflow_version_id=request.workflow_version_id,
                sample_input=request.sample_input,
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/feedback")
    async def research_feedback() -> dict[str, Any]:
        return app_state.research_feedback_service().snapshot()

    @app.post("/api/v1/feedback/refresh")
    async def refresh_research_feedback() -> dict[str, Any]:
        return app_state.research_feedback_service().refresh()

    @app.get("/api/v1/notifications")
    async def notifications(status: str | None = None, limit: int = 100) -> dict[str, Any]:
        try:
            return app_state.research_feedback_service().list_notifications(status=status, limit=limit)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.patch("/api/v1/notifications/{notification_id}")
    async def update_notification(notification_id: str, request: NotificationStatusRequest) -> dict[str, Any]:
        try:
            return app_state.research_feedback_service().update_notification(notification_id, request.status)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/config")
    async def config() -> dict[str, Any]:
        return app_state.config_payload()

    @app.get("/api/v1/setup")
    async def setup() -> dict[str, Any]:
        return app_state.setup_payload()

    @app.post("/api/v1/setup/apply")
    async def apply_setup(request: SetupProfileApplyRequest) -> dict[str, Any]:
        try:
            return app_state.apply_setup_profile(request.profile_id, request.preserve_existing)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.put("/api/v1/config")
    async def update_config(request: SystemConfigUpdateRequest) -> dict[str, Any]:
        try:
            return app_state.update_config(request.values, request.clear_keys)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/api/v1/ai/config")
    async def ai_config() -> dict[str, Any]:
        return app_state.ai_config_payload()

    @app.put("/api/v1/ai/config")
    async def update_ai_config(request: AIConfigUpdateRequest) -> dict[str, Any]:
        try:
            return app_state.update_ai_config(request.model_dump())
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.post("/api/v1/ai/config/models/refresh")
    async def refresh_ai_models(request: AIModelRefreshRequest) -> dict[str, Any]:
        try:
            return app_state.refresh_ai_models(request.site_id, request.protocol)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except Exception as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc

    @app.get("/api/v1/jobs")
    async def jobs(limit: int = 50) -> dict[str, Any]:
        return {"status": "ok", "jobs": app_state.jobs.list_jobs(limit=limit)}

    @app.get("/api/v1/jobs/{job_id}")
    async def job_detail(job_id: str, traceback: bool = False) -> dict[str, Any]:
        record = app_state.jobs.get(job_id)
        if record is None:
            raise HTTPException(status_code=404, detail=f"未找到任务：{job_id}")
        return record.to_dict(include_traceback=traceback)

    @app.get("/api/v1/reports/latest")
    async def latest_report(kind: str = "operator-workbench") -> dict[str, Any]:
        return _latest_report_or_400(app_state, kind)

    @app.get("/api/v1/reports/latest/{kind}")
    async def latest_report_by_kind(kind: str) -> dict[str, Any]:
        return _latest_report_or_400(app_state, kind)

    @app.get("/api/v1/operator/workbench/latest")
    async def latest_operator_workbench() -> dict[str, Any]:
        return _latest_report_or_400(app_state, "operator-workbench")

    @app.get("/api/v1/evaluations/recommendations/latest")
    async def latest_recommendation_evaluation() -> dict[str, Any]:
        return _latest_report_or_400(app_state, "recommendation-evaluation")

    @app.get("/api/v1/portfolio-risk/latest")
    async def latest_portfolio_risk() -> dict[str, Any]:
        return _latest_report_or_400(app_state, "portfolio-risk")

    @app.get("/api/v1/ai/governance/latest")
    async def latest_ai_governance() -> dict[str, Any]:
        return _latest_report_or_400(app_state, "ai-governance")

    @app.get("/api/v1/proxy/diagnostics")
    async def proxy_diagnostics(start_bridges: bool = False) -> dict[str, Any]:
        return app_state.run_proxy_diagnostics({"start_bridges": start_bridges})

    @app.post("/api/v1/jobs/research-pipeline", status_code=202)
    async def submit_research_pipeline(request: ResearchPipelineJobRequest, background_tasks: BackgroundTasks) -> dict[str, Any]:
        record = app_state.submit_research_pipeline(request.model_dump(exclude_unset=True), background_tasks)
        return {"status": "accepted", "job": record.to_dict()}

    @app.post("/api/v1/jobs/operator-workbench", status_code=202)
    async def submit_operator_workbench(request: OperatorWorkbenchJobRequest, background_tasks: BackgroundTasks) -> dict[str, Any]:
        record = app_state.submit_operator_workbench(request.model_dump(exclude_unset=True), background_tasks)
        return {"status": "accepted", "job": record.to_dict()}

    @app.post("/api/v1/jobs/proxy-diagnostics", status_code=202)
    async def submit_proxy_diagnostics(request: ProxyDiagnosticsJobRequest, background_tasks: BackgroundTasks) -> dict[str, Any]:
        record = app_state.submit_proxy_diagnostics(request.model_dump(exclude_unset=True), background_tasks)
        return {"status": "accepted", "job": record.to_dict()}

    dist_path = Path(frontend_dist) if frontend_dist else None
    if dist_path and dist_path.exists():
        assets_path = dist_path / "assets"
        if assets_path.exists():
            app.mount("/assets", StaticFiles(directory=assets_path), name="assets")

        @app.get("/", response_class=FileResponse)
        async def frontend_index() -> FileResponse:
            return FileResponse(dist_path / "index.html")

        @app.get("/{path:path}")
        async def frontend_spa(path: str) -> Response:
            if path == "api" or path.startswith("api/"):
                return JSONResponse(
                    status_code=404,
                    content={"detail": "API endpoint not found", "code": "api_not_found"},
                )
            target = dist_path / path
            if target.exists() and target.is_file():
                return FileResponse(target)
            return FileResponse(dist_path / "index.html")
    else:

        @app.get("/", response_class=HTMLResponse)
        async def service_home() -> str:
            return _service_home_html()

    return app


def _latest_report_or_400(app_state: FinBotWebApp, kind: str) -> dict[str, Any]:
    try:
        return app_state.latest_report(kind)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def _instant_focus_queries(query: str) -> list[str]:
    return list(dict.fromkeys((query, f"{query} 官方来源 最新", f"{query} 市场影响 数据")))


def _instant_product_context(value: dict[str, Any] | None) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    return {
        key: str(value.get(key) or "").strip()[:120]
        for key in ("product_id", "preferred_instrument_id", "watchlist_id", "provider", "market_type")
        if str(value.get(key) or "").strip()
    }


def _autonomous_request_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["payload"] = _loads(payload.pop("payload_json", "{}"), {})
    payload["result"] = _loads(payload.pop("result_json", "{}"), {})
    return payload


def _instant_research_summary_payload(store: Any, row: Any) -> dict[str, Any]:
    request = _autonomous_request_payload(row)
    loop_run_id = request.get("loop_run_id")
    loop_run = None
    if loop_run_id:
        loop_row = store.get_autonomous_loop_run(str(loop_run_id))
        if loop_row is not None:
            loop_run = {
                "status": loop_row["status"],
                "steps": [
                    {"step_name": step["step_name"], "status": step["status"]}
                    for step in store.latest_autonomous_loop_steps(str(loop_run_id))
                ],
            }
    return {
        "session_id": str(request["request_id"]),
        "query": str((request.get("payload") or {}).get("query") or ""),
        "product_context": (request.get("payload") or {}).get("product_context") or {},
        "status": request["status"],
        "stage": _instant_stage(request, loop_run),
        "requested_at": request["requested_at"],
        "started_at": request.get("started_at"),
        "finished_at": request.get("finished_at"),
        "error": request.get("error"),
        "loop_run_id": loop_run_id,
        "progress": _instant_progress(request, loop_run),
    }


def _instant_research_pipeline_payload(store: Any, loop_run_id: str) -> dict[str, Any] | None:
    triggered_by = f"autonomous:{loop_run_id}"
    row = next(
        (item for item in store.list_research_pipeline_runs(limit=50) if item["triggered_by"] == triggered_by),
        None,
    )
    if row is None:
        return None
    steps = [
        _compact_instant_step(
            {
            "step_id": step["step_id"],
            "step_name": step["step_name"],
            "status": step["status"],
            "attempt": step["attempt"],
            "started_at": step["started_at"],
            "finished_at": step["finished_at"],
            "duration_ms": step["duration_ms"],
            "input": _loads(step["input_json"], {}),
            "output": _loads(step["output_json"], {}),
            "error": step["error"],
            }
        )
        for step in store.latest_research_pipeline_steps(row["run_id"])
    ]
    return {
        "run_id": row["run_id"],
        "profile": row["profile"],
        "status": row["status"],
        "triggered_by": row["triggered_by"],
        "config": _loads(row["config_json"], {}),
        "summary": _loads(row["summary_json"], {}),
        "started_at": row["started_at"],
        "finished_at": row["finished_at"],
        "error": row["error"],
        "steps": steps,
    }


def _instant_loop_run_payload(loop_run: dict[str, Any]) -> dict[str, Any]:
    return {
        **loop_run,
        "steps": [
            _compact_instant_step(step)
            for step in loop_run.get("steps", [])
            if isinstance(step, dict)
        ],
    }


def _compact_instant_step(step: dict[str, Any]) -> dict[str, Any]:
    output = step.get("output") if isinstance(step.get("output"), dict) else {}
    output_summary = {
        key: output[key]
        for key in ("status", "run_id", "report_id", "debate_id", "council_id")
        if key in output and isinstance(output[key], (str, int, float, bool, type(None)))
    }
    for key in ("results", "items", "candidates", "ai_decisions", "recommended_products"):
        value = output.get(key)
        if isinstance(value, dict) and isinstance(value.get("count"), (int, float)):
            output_summary[key] = {"count": value["count"]}
        elif isinstance(value, list):
            output_summary[key] = {"count": len(value)}
    return {**step, "input": {}, "output": output_summary}


def _instant_recommendations(
    loop_run: dict[str, Any],
    decisions: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    product_step = next(
        (step for step in loop_run.get("steps", []) if step.get("step_name") == "product_selection"),
        None,
    )
    if isinstance(product_step, dict):
        recommended = (product_step.get("output") or {}).get("recommended_products")
        if isinstance(recommended, dict) and isinstance(recommended.get("sample"), list):
            return [item for item in recommended["sample"] if isinstance(item, dict)]
    return [
        {
            key: decision.get(key)
            for key in (
                "symbol",
                "provider",
                "market_type",
                "action",
                "status",
                "confidence",
                "score",
                "entry_reference",
                "target_price",
                "invalidation_price",
            )
        }
        for decision in decisions[:5]
    ]


def _instant_progress(request: dict[str, Any], loop_run: dict[str, Any] | None) -> dict[str, Any]:
    steps = loop_run.get("steps", []) if isinstance(loop_run, dict) else []
    completed = sum(step.get("status") in {"passed", "skipped", "failed"} for step in steps)
    running_step = next((step.get("step_name") for step in steps if step.get("status") == "running"), None)
    return {
        "completed_steps": completed,
        "total_steps": 13,
        "running_step": running_step,
        "queue_status": request.get("status"),
    }


def _instant_stage(request: dict[str, Any], loop_run: dict[str, Any] | None) -> str:
    request_status = str(request.get("status") or "queued")
    if request_status == "queued":
        return "queued"
    if request_status in {"succeeded", "partial"}:
        return "result"
    if request_status in {"failed", "cancelled"}:
        return "failed"
    if not loop_run:
        return "preparing"
    running_step = next(
        (str(step.get("step_name")) for step in loop_run.get("steps", []) if step.get("status") == "running"),
        "preparing",
    )
    if running_step == "research_pipeline":
        return "collect"
    if running_step in {
        "instrument_catalog",
        "universe_selection",
        "operator_workbench",
        "product_candidates",
        "ai_debate",
        "trade_synthesis",
    }:
        return "analyze"
    return "result"


def _autonomous_run_payload(store: Any, row: Any, include_steps: bool = False) -> dict[str, Any]:
    summary = _loads(row["summary_json"], {})
    payload = {
        "loop_run_id": row["loop_run_id"],
        "status": row["status"],
        "trigger_type": row["trigger_type"],
        "config": _loads(row["config_json"], {}),
        "summary": summary,
        "decision_readiness": summary.get("decision_readiness") if isinstance(summary, dict) else None,
        "started_at": row["started_at"],
        "finished_at": row["finished_at"],
        "error": row["error"],
    }
    if include_steps:
        payload["steps"] = [
            {
                "step_id": step["step_id"],
                "step_name": step["step_name"],
                "status": step["status"],
                "attempt": step["attempt"],
                "started_at": step["started_at"],
                "finished_at": step["finished_at"],
                "duration_ms": step["duration_ms"],
                "input": _loads(step["input_json"], {}),
                "output": _loads(step["output_json"], {}),
                "error": step["error"],
            }
            for step in store.latest_autonomous_loop_steps(row["loop_run_id"])
        ]
    return payload


def _ai_debate_payload(row: Any, store: Any | None = None) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    messages = payload.get("messages") if isinstance(payload.get("messages"), list) else []
    if store is not None:
        message_rows = store.list_ai_debate_messages(debate_id=row["debate_id"], limit=50)
        if message_rows:
            messages = [_ai_debate_message_row_payload(message) for message in reversed(message_rows)]
    return {
        "debate_id": row["debate_id"],
        "loop_run_id": row["loop_run_id"],
        "research_pipeline_run_id": row["research_pipeline_run_id"],
        "operator_report_id": row["operator_report_id"],
        "template_id": row["template_id"],
        "status": row["status"],
        "protocol": row["protocol"],
        "provider": row["provider"],
        "model": row["model"],
        "rounds": row["rounds"],
        "summary": _loads(row["summary_json"], {}),
        "round_summaries": _loads(row["round_summaries_json"], []),
        "messages": [
            {
                "message_id": message.get("message_id"),
                "round_index": message.get("round_index"),
                "turn_index": message.get("turn_index"),
                "phase_id": message.get("phase_id"),
                "message_type": message.get("message_type"),
                "reply_to_message_ids": message.get("reply_to_message_ids") or [],
                "agent_role": message.get("agent_role"),
                "stance": message.get("stance"),
                "status": message.get("status"),
                "provider": message.get("provider"),
                "model": message.get("model"),
                "content": message.get("content"),
                "error": message.get("error"),
            }
            for message in messages[:50]
            if isinstance(message, dict)
        ],
        "error": row["error"],
        "created_at": row["created_at"],
    }


def _ai_debate_message_row_payload(row: Any) -> dict[str, Any]:
    return {
        "message_id": row["message_id"],
        "round_index": row["round_index"],
        "turn_index": row["turn_index"],
        "phase_id": row["phase_id"],
        "message_type": row["message_type"],
        "reply_to_message_ids": _loads(row["reply_to_json"], []),
        "agent_role": row["agent_role"],
        "stance": row["stance"],
        "status": row["status"],
        "provider": row["provider"],
        "model": row["model"],
        "content": _loads(row["content_json"], {}),
        "error": row["error"],
    }


def _ai_trade_decision_payload(row: Any) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    return {
        "decision_id": row["decision_id"],
        "loop_run_id": row["loop_run_id"],
        "debate_id": row["debate_id"],
        "source_report_id": row["source_report_id"],
        "candidate_id": row["candidate_id"],
        "provider": row["provider"],
        "market_type": row["market_type"],
        "symbol": row["symbol"],
        "normalized_symbol": row["normalized_symbol"],
        "action": row["action"],
        "status": row["status"],
        "confidence": row["confidence"],
        "score": row["score"],
        "horizon": row["horizon"],
        "entry_reference": row["entry_reference"],
        "target_price": row["target_price"],
        "invalidation_price": row["invalidation_price"],
        "position_sizing": _loads(row["position_sizing_json"], {}),
        "rationale": _loads(row["rationale_json"], []),
        "risk_warnings": _loads(row["risk_warnings_json"], []),
        "evidence_refs": _loads(row["evidence_refs_json"], []),
        "policy": _loads(row["policy_json"], {}),
        "research_context": payload.get("research_context") or {},
        "ai_site_id": row["ai_site_id"],
        "ai_model": row["ai_model"],
        "prompt_version": row["prompt_version"],
        "experiment_id": row["experiment_id"],
        "variant_id": row["variant_id"],
        "ai_provenance": payload.get("ai_provenance") or {},
        "created_at": row["created_at"],
    }


def _paper_execution_run_payload(row: Any) -> dict[str, Any]:
    return {
        "execution_run_id": row["execution_run_id"],
        "loop_run_id": row["loop_run_id"],
        "status": row["status"],
        "config": _loads(row["config_json"], {}),
        "summary": _loads(row["summary_json"], {}),
        "created_at": row["created_at"],
        "finished_at": row["finished_at"],
    }


def _paper_execution_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["request"] = _loads(payload.pop("request_json", "{}"), {})
    payload["response"] = _loads(payload.pop("response_json", "{}"), {})
    return payload


def _credential_fingerprint(api_key: Any, api_secret: Any) -> str | None:
    key = str(api_key or "").strip()
    secret = str(api_secret or "").strip()
    if not key or not secret:
        return None
    return hashlib.sha256(f"{key}\0{secret}".encode("utf-8")).hexdigest()[:16]


def _credential_probe_summary(
    *,
    adapter_id: str,
    probe_payload: dict[str, Any],
    credential_fingerprint: str | None,
) -> dict[str, Any]:
    targets = probe_payload.get("targets") if isinstance(probe_payload, dict) else None
    target = targets.get(adapter_id) if isinstance(targets, dict) else None
    checked_at = probe_payload.get("generated_at") if isinstance(probe_payload, dict) else None
    if not isinstance(target, dict):
        return {
            "status": "unverified",
            "checked_at": None,
            "attempt_count": 0,
            "reason": "尚未运行只读凭据探针",
        }
    if not credential_fingerprint or target.get("credential_fingerprint") != credential_fingerprint:
        return {
            "status": "unverified",
            "checked_at": checked_at,
            "attempt_count": 0,
            "reason": "当前凭据尚未运行只读鉴权",
        }

    raw_status = str(target.get("status") or "unverified")
    status = raw_status if raw_status in {"passed", "failed"} else "unverified"
    attempts = [item for item in target.get("attempts") or [] if isinstance(item, dict)]
    last_attempt = attempts[-1] if attempts else {}
    code = last_attempt.get("code")
    http_status = last_attempt.get("http_status")
    message = str(last_attempt.get("message") or "")
    if status == "passed":
        reason = "只读鉴权已通过"
    elif code == "INVALID_KEY":
        reason = "Gate TestNet 未识别当前 API key"
    elif http_status == 403 and "country" in message.lower():
        reason = "当前代理出口被 Bybit 地区策略拦截"
    elif status == "failed":
        reason = f"只读鉴权失败{f'（{code}）' if code not in (None, '') else ''}"
    else:
        reason = "只读鉴权状态未知"
    return {
        "status": status,
        "checked_at": checked_at,
        "attempt_count": len(attempts),
        "last_http_status": http_status,
        "last_code": code,
        "reason": reason,
    }


def _config_value(app_state: FinBotWebApp, key: str, default: Any = None) -> Any:
    if app_state.config_store is None:
        return default
    try:
        return app_state.config_store.value(key, default)
    except ValueError:
        raise
    except Exception:
        return default


def _request_value(app_state: FinBotWebApp, request: dict[str, Any], request_key: str, config_key: str, default: Any) -> Any:
    if request_key in request:
        return request[request_key]
    return _config_value(app_state, config_key, default)


def _proxy_policy_values(policy_file: str | None) -> dict[str, Any]:
    policy = load_proxy_policy(policy_file)
    values: dict[str, Any] = {
        "proxy_policy.exchange.allow_direct": policy.exchange_allow_direct,
    }
    for provider in ("binance", "bybit", "gate"):
        route = f"exchange:{provider}"
        override = policy.exchange_provider_overrides.get(route) or {}
        if "allow_direct" in override:
            values[f"proxy_policy.exchange.{provider}.allow_direct"] = _bool_value(override.get("allow_direct"), False)
    return values


def _update_proxy_policy(policy_file: str | None, updates: dict[str, Any], clear_keys: list[str]) -> None:
    if not policy_file:
        raise ValueError("未配置代理策略文件路径")
    path = Path(policy_file)
    payload = _read_proxy_policy_payload(path)
    routes = payload.setdefault("routes", {})
    if not isinstance(routes, dict):
        raise ValueError(f"代理策略文件无效：{path}: routes 必须是对象")
    for key in clear_keys:
        route_key, field_name = _proxy_policy_target(key)
        route = routes.get(route_key)
        if isinstance(route, dict):
            route.pop(field_name, None)
    for key, value in updates.items():
        route_key, field_name = _proxy_policy_target(key)
        spec = CONFIG_FIELD_MAP[key]
        route = routes.setdefault(route_key, {})
        if not isinstance(route, dict):
            raise ValueError(f"代理策略文件无效：{path}: {route_key} 必须是对象")
        route[field_name] = normalize_value(spec, value)
        route.setdefault("description", "由 FinBot 系统配置界面热更")
    payload.setdefault("description", "FinBot local hot proxy policy. This file is gitignored.")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    tmp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    tmp_path.replace(path)


def _read_proxy_policy_payload(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"routes": {}}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"代理策略 JSON 无效：{path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"代理策略 JSON 无效：{path}: 根节点必须是对象")
    return payload


def _proxy_policy_target(key: str) -> tuple[str, str]:
    target = PROXY_POLICY_KEYS.get(key)
    if target is None:
        raise ValueError(f"不支持的代理策略项：{key}")
    return target


def _service_home_html() -> str:
    return """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>FinBot 网页服务</title>
  <style>
    body { font-family: Arial, sans-serif; margin: 32px; color: #202124; background: #f7f8fa; }
    main { max-width: 960px; margin: 0 auto; }
    section { background: white; border: 1px solid #dfe3e8; border-radius: 8px; padding: 18px; margin: 16px 0; }
    pre { background: #f1f3f4; border-radius: 6px; padding: 12px; overflow: auto; }
  </style>
</head>
<body>
<main>
  <h1>FinBot 网页服务</h1>
  <section>
    <pre>GET  /health
GET  /docs
GET  /api/v1/status
GET  /api/v1/autonomous/status
GET  /api/v1/paper-execution/status
POST /api/v1/autonomous/run-now
GET  /api/v1/config
PUT  /api/v1/config
GET  /api/v1/setup
POST /api/v1/setup/apply
GET  /api/v1/ai/config
PUT  /api/v1/ai/config
POST /api/v1/ai/config/models/refresh
GET  /api/v1/jobs
GET  /api/v1/jobs/{job_id}
GET  /api/v1/reports/latest/{kind}
POST /api/v1/jobs/research-pipeline
POST /api/v1/jobs/operator-workbench
POST /api/v1/jobs/proxy-diagnostics</pre>
  </section>
</main>
</body>
</html>"""


def _compact_steps(steps: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "step_name": step.get("step_name") or step.get("name"),
            "status": step.get("status"),
            "attempt": step.get("attempt"),
            "duration_ms": step.get("duration_ms"),
            "error": step.get("error"),
        }
        for step in steps
    ]


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        loaded = json.loads(value)
        return default if loaded is None else loaded
    except Exception:
        return default


def _job_id(kind: str, request: dict[str, Any], created_at: str) -> str:
    payload = json.dumps(request, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(f"{kind}:{created_at}:{payload}".encode("utf-8")).hexdigest()[:24]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _recent_timestamp(value: Any, max_age_seconds: float) -> bool:
    if not value:
        return False
    try:
        parsed = datetime.fromisoformat(str(value))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return False
    return (datetime.now(timezone.utc) - parsed).total_seconds() <= max_age_seconds


def _optional_str(value: Any) -> str | None:
    if value is None:
        return None
    clean = str(value).strip()
    return clean or None


def _list_value(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    if isinstance(value, (list, tuple)):
        return [str(item).strip() for item in value if str(item).strip()]
    return [str(value).strip()] if str(value).strip() else []


def _bool_value(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        clean = value.strip().lower()
        if clean in {"1", "true", "yes", "y", "on"}:
            return True
        if clean in {"0", "false", "no", "n", "off"}:
            return False
    return default


def _int_value(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))


def _optional_int(value: Any, minimum: int, maximum: int) -> int | None:
    if value is None or value == "":
        return None
    return _int_value(value, minimum, minimum, maximum)


def _float_value(value: Any, default: float, minimum: float, maximum: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = default
    return max(minimum, min(maximum, parsed))
