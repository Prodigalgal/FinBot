from __future__ import annotations

import json
import inspect
import os
import re
import socket
import threading
import uuid
from collections.abc import Callable
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any

from finbot.autonomous.config import AutonomousLoopConfig
from finbot.autonomous.runner import AutonomousResearchLoopRunner
from finbot.storage.sqlite_store import SQLiteStore


ConfigLoader = Callable[[], AutonomousLoopConfig]
StoreLoader = Callable[[AutonomousLoopConfig], SQLiteStore]
RunnerFactory = Callable[[AutonomousLoopConfig], AutonomousResearchLoopRunner]
INSTANT_RESEARCH_TRIGGER = "instant-research"
REPLAY_TRIGGER = "replay"
REPLAY_CONFIG_FIELDS = frozenset(
    {
        "profile", "continue_on_error", "run_research_pipeline", "run_ingestion",
        "max_initial_jobs", "run_ai_compression", "ai_compression_dry_run",
        "run_followups", "followups_dry_run", "max_events", "include_background_council",
        "run_instrument_catalog", "universe_mode", "universe_quote_assets",
        "universe_max_instruments", "universe_min_turnover_24h", "universe_max_spread_pct",
        "run_operator_workbench", "run_ai_debate", "workflow_engine_version", "workflow_depth",
        "workflow_director_enabled", "workflow_learning_enabled", "ai_debate_rounds", "council_template_id",
        "ai_debate_max_candidates", "ai_trade_min_confidence",
        "ai_trade_require_research_confirmation", "symbols", "providers", "intervals",
        "candle_limit", "recommendation_min_confidence", "max_recommendations",
    }
)


class AutonomousRequestQueue:
    def __init__(self, store: SQLiteStore):
        self.store = store
        self.store.init_schema()

    def enqueue(
        self,
        trigger_type: str = "manual",
        payload: dict[str, Any] | None = None,
        dedupe_key: str | None = None,
        available_at: str | None = None,
    ) -> dict[str, Any]:
        requested_at = _now()
        request_id = uuid.uuid4().hex
        row = self.store.enqueue_autonomous_request(
            {
                "request_id": request_id,
                "trigger_type": trigger_type,
                "requested_at": requested_at,
                "available_at": available_at or requested_at,
                "dedupe_key": dedupe_key,
                "payload": payload or {},
            }
        )
        return _request_payload(row)

    def snapshot(self, request_limit: int = 20) -> dict[str, Any]:
        snapshot = self.store.autonomous_worker_snapshot(request_limit=request_limit)
        snapshot["recent_requests"] = [_request_payload(row) for row in snapshot["recent_requests"]]
        for worker in snapshot["workers"]:
            worker["metadata"] = _loads(worker.pop("metadata_json", "{}"), {})
        for lease in snapshot["leases"]:
            lease["metadata"] = _loads(lease.pop("metadata_json", "{}"), {})
        if snapshot["scheduler"]:
            snapshot["scheduler"]["metadata"] = _loads(
                snapshot["scheduler"].pop("metadata_json", "{}"),
                {},
            )
        return snapshot


class AutonomousWorker:
    LEASE_NAME = "autonomous-scheduler"

    def __init__(
        self,
        config_loader: ConfigLoader,
        store_loader: StoreLoader,
        runner_factory: RunnerFactory | None = None,
        worker_id: str | None = None,
        poll_seconds: float = 2.0,
        lease_seconds: float = 30.0,
        heartbeat_seconds: float = 5.0,
    ):
        self.config_loader = config_loader
        self.store_loader = store_loader
        self.runner_factory = runner_factory or (lambda _config: AutonomousResearchLoopRunner())
        self.worker_id = worker_id or f"{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}"
        self.poll_seconds = max(0.1, float(poll_seconds))
        self.lease_seconds = max(5.0, float(lease_seconds))
        self.heartbeat_seconds = max(1.0, min(float(heartbeat_seconds), self.lease_seconds / 2))
        self.started_at = _now()
        self._stop_event = threading.Event()
        self._current_request_id: str | None = None
        self._last_loop_run_id: str | None = None
        self._last_error: str | None = None
        self._last_store: SQLiteStore | None = None

    def stop(self) -> None:
        self._stop_event.set()

    def run_forever(self) -> None:
        try:
            while not self._stop_event.is_set():
                self.tick()
                self._stop_event.wait(self.poll_seconds)
        finally:
            if self._last_store is not None:
                self._heartbeat(self._last_store, "stopped")
                self._last_store.release_autonomous_worker_lease(self.LEASE_NAME, self.worker_id)

    def tick(self) -> dict[str, Any]:
        try:
            config = self.config_loader()
            store = self.store_loader(config)
            store.init_schema()
            self._last_store = store
            lease_owned = self._renew_lease(store)
            if not lease_owned:
                self._heartbeat(store, "standby")
                return {"status": "standby", "worker_id": self.worker_id}
            recovery = store.reconcile_stale_autonomous_runs(
                now=_now(),
                orphaned_before=(datetime.now(timezone.utc) - timedelta(minutes=30)).isoformat(),
            )
            if recovery["loop_count"] or recovery["pipeline_count"]:
                self._last_error = (
                    f"reconciled stale runs: loops={recovery['loop_count']}, "
                    f"pipelines={recovery['pipeline_count']}"
                )
            self._heartbeat(store, "idle")
            self._enqueue_scheduled_if_due(store, config)
            request = store.claim_autonomous_request(
                worker_id=self.worker_id,
                now=_now(),
                lease_expires_at=_future(self.lease_seconds),
            )
            if request is None:
                return {"status": "idle", "worker_id": self.worker_id}
            return self._process_request(store, config, request)
        except Exception as exc:
            self._last_error = f"{type(exc).__name__}: {exc}"
            if self._last_store is not None:
                self._heartbeat(self._last_store, "error")
            return {"status": "error", "worker_id": self.worker_id, "error": self._last_error}

    def _process_request(
        self,
        store: SQLiteStore,
        config: AutonomousLoopConfig,
        request: Any,
    ) -> dict[str, Any]:
        request_id = str(request["request_id"])
        self._current_request_id = request_id
        self._last_error = None
        heartbeat_stop = threading.Event()
        heartbeat_thread = threading.Thread(
            target=self._run_request_heartbeat,
            args=(store, request_id, heartbeat_stop),
            name=f"finbot-worker-heartbeat-{self.worker_id}",
            daemon=True,
        )
        heartbeat_thread.start()
        self._heartbeat(store, "running")
        try:
            trigger_type = str(request["trigger_type"])
            raw_payload = _loads(request["payload_json"], {})
            request_context = _request_context(trigger_type, raw_payload)
            request_config = _config_for_request(config, trigger_type, request_context)
            runner = self.runner_factory(request_config)
            run_parameters = inspect.signature(runner.run).parameters
            run_kwargs: dict[str, Any] = {}
            if "request_context" in run_parameters:
                run_kwargs["request_context"] = request_context
            if "request_id" in run_parameters:
                run_kwargs["request_id"] = request_id
            result = runner.run(request_config, trigger_type=trigger_type, **run_kwargs)
            result_status = str(result.get("status") or "failed").lower()
            request_status = "succeeded" if result_status == "passed" else "partial" if result_status == "partial" else "failed"
            loop_run_id = result.get("loop_run_id")
            error = (result.get("summary") or {}).get("first_error") if isinstance(result.get("summary"), dict) else None
            store.finish_autonomous_request(
                request_id=request_id,
                worker_id=self.worker_id,
                status=request_status,
                finished_at=_now(),
                loop_run_id=loop_run_id,
                result={
                    "status": result_status,
                    "loop_run_id": loop_run_id,
                    "summary": result.get("summary", {}),
                    "output": result.get("output"),
                },
                error=error,
            )
            self._last_loop_run_id = str(loop_run_id) if loop_run_id else None
            self._last_error = str(error) if error else None
            return {
                "status": request_status,
                "worker_id": self.worker_id,
                "request_id": request_id,
                "loop_run_id": loop_run_id,
            }
        except Exception as exc:
            self._last_error = f"{type(exc).__name__}: {exc}"
            store.finish_autonomous_request(
                request_id=request_id,
                worker_id=self.worker_id,
                status="failed",
                finished_at=_now(),
                loop_run_id=None,
                result={},
                error=self._last_error,
            )
            return {
                "status": "failed",
                "worker_id": self.worker_id,
                "request_id": request_id,
                "error": self._last_error,
            }
        finally:
            heartbeat_stop.set()
            heartbeat_thread.join(timeout=self.heartbeat_seconds + 1)
            self._current_request_id = None
            self._heartbeat(store, "stopping" if self._stop_event.is_set() else "idle")

    def _run_request_heartbeat(
        self,
        store: SQLiteStore,
        request_id: str,
        stop_event: threading.Event,
    ) -> None:
        while not stop_event.wait(self.heartbeat_seconds):
            try:
                self._renew_lease(store)
                store.heartbeat_autonomous_request(request_id, self.worker_id, _future(self.lease_seconds))
                self._heartbeat(store, "running")
            except Exception as exc:
                self._last_error = f"heartbeat {type(exc).__name__}: {exc}"

    def _renew_lease(self, store: SQLiteStore) -> bool:
        now = _now()
        return store.renew_autonomous_worker_lease(
            lease_name=self.LEASE_NAME,
            owner_id=self.worker_id,
            now=now,
            expires_at=_future(self.lease_seconds),
            metadata={"process_id": os.getpid(), "hostname": socket.gethostname()},
        )

    def _heartbeat(self, store: SQLiteStore, status: str) -> None:
        store.upsert_autonomous_worker_heartbeat(
            {
                "worker_id": self.worker_id,
                "status": status,
                "process_id": os.getpid(),
                "hostname": socket.gethostname(),
                "started_at": self.started_at,
                "heartbeat_at": _now(),
                "current_request_id": self._current_request_id,
                "last_loop_run_id": self._last_loop_run_id,
                "last_error": self._last_error,
                "metadata": {
                    "poll_seconds": self.poll_seconds,
                    "lease_seconds": self.lease_seconds,
                    "heartbeat_seconds": self.heartbeat_seconds,
                },
            }
        )

    def _enqueue_scheduled_if_due(self, store: SQLiteStore, config: AutonomousLoopConfig) -> None:
        now = datetime.now(timezone.utc)
        state = store.get_autonomous_scheduler_state()
        if not config.enabled:
            state_metadata = _loads(state["metadata_json"], {}) if state else {}
            if (
                state is not None
                and state["next_run_at"] is None
                and state_metadata.get("enabled") is False
                and state_metadata.get("interval_minutes") == config.interval_minutes
            ):
                return
            store.upsert_autonomous_scheduler_state(
                next_run_at=None,
                last_enqueued_at=state["last_enqueued_at"] if state else None,
                last_request_id=state["last_request_id"] if state else None,
                updated_at=now.isoformat(),
                metadata={"enabled": False, "interval_minutes": config.interval_minutes},
            )
            return
        next_run_at = _parse_datetime(state["next_run_at"]) if state and state["next_run_at"] else None
        if next_run_at is not None and now < next_run_at:
            return
        due_at = next_run_at or now
        request = AutonomousRequestQueue(store).enqueue(
            trigger_type="scheduler",
            payload={"scheduled_for": due_at.isoformat()},
            dedupe_key=f"scheduler:{due_at.isoformat()}",
        )
        next_at = now + timedelta(minutes=max(1, config.interval_minutes))
        store.upsert_autonomous_scheduler_state(
            next_run_at=next_at.isoformat(),
            last_enqueued_at=now.isoformat(),
            last_request_id=request["request_id"],
            updated_at=now.isoformat(),
            metadata={"enabled": True, "interval_minutes": config.interval_minutes},
        )


def _request_payload(row: Any) -> dict[str, Any]:
    value = dict(row)
    value["payload"] = _loads(value.pop("payload_json", "{}"), {})
    value["result"] = _loads(value.pop("result_json", "{}"), {})
    return value


def _loads(value: str, default: Any) -> Any:
    try:
        return json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return default


def _request_context(trigger_type: str, payload: dict[str, Any]) -> dict[str, Any]:
    if trigger_type != INSTANT_RESEARCH_TRIGGER:
        return dict(payload)
    query = str(payload.get("query") or "").strip()
    if not query:
        raise ValueError("即时研究缺少 query")
    focus_queries = [str(value).strip() for value in payload.get("focus_queries", []) if str(value).strip()]
    if not focus_queries:
        focus_queries = _instant_focus_queries(query)
    symbols = [str(value).strip().upper() for value in payload.get("symbols", []) if str(value).strip()]
    product_context = payload.get("product_context") if isinstance(payload.get("product_context"), dict) else {}
    return {
        "mode": INSTANT_RESEARCH_TRIGGER,
        "query": query[:500],
        "focus_queries": focus_queries[:5],
        "symbols": symbols[:12],
        "product_context": {
            key: str(product_context.get(key) or "").strip()[:120]
            for key in ("product_id", "preferred_instrument_id", "watchlist_id", "provider", "market_type")
            if str(product_context.get(key) or "").strip()
        },
        "requested_by": str(payload.get("requested_by") or "web-api")[:80],
    }


def _config_for_request(
    config: AutonomousLoopConfig,
    trigger_type: str,
    request_context: dict[str, Any],
) -> AutonomousLoopConfig:
    if trigger_type == REPLAY_TRIGGER:
        raw_overrides = request_context.get("replay_config")
        overrides = {
            key: value
            for key, value in (raw_overrides.items() if isinstance(raw_overrides, dict) else [])
            if key in REPLAY_CONFIG_FIELDS
        }
        for key in ("symbols", "providers", "intervals", "universe_quote_assets"):
            if key in overrides:
                normalized = _replay_sequence(overrides[key])
                if normalized:
                    overrides[key] = normalized
                else:
                    overrides.pop(key)
        return replace(
            config,
            **overrides,
            paper_execution_submit_orders=False,
            paper_execution_require_human_review=True,
        )
    if trigger_type != INSTANT_RESEARCH_TRIGGER:
        return config
    symbols = _instant_symbols(str(request_context.get("query") or ""), request_context.get("symbols"), config.symbols)
    return replace(
        config,
        profile="instant-research",
        continue_on_error=True,
        run_research_pipeline=True,
        run_ingestion=True,
        run_ai_compression=True,
        ai_compression_dry_run=False,
        run_followups=True,
        followups_dry_run=False,
        run_instrument_catalog=True,
        run_operator_workbench=True,
        run_ai_debate=True,
        paper_execution_enabled=False,
        paper_execution_submit_orders=False,
        symbols=symbols,
    )


def _instant_focus_queries(query: str) -> list[str]:
    return list(
        dict.fromkeys(
            (
                query,
                f"{query} 官方来源 最新",
                f"{query} 市场影响 数据",
            )
        )
    )


def _instant_symbols(query: str, explicit: Any, defaults: tuple[str, ...]) -> tuple[str, ...]:
    candidates = [str(value).strip().upper() for value in explicit or [] if str(value).strip()]
    upper_query = query.upper()
    candidates.extend(re.findall(r"\b[A-Z0-9]{2,12}(?:USDT|USD)\b", upper_query))
    aliases = (
        (("比特币", "BITCOIN", "BTC"), "BTCUSDT"),
        (("以太坊", "ETHEREUM", "ETH"), "ETHUSDT"),
        (("SOLANA", "SOL"), "SOLUSDT"),
    )
    for terms, symbol in aliases:
        if any(term in query or term in upper_query for term in terms):
            candidates.append(symbol)
    normalized = tuple(dict.fromkeys(value for value in candidates if value))
    return normalized[:12] or defaults


def _replay_sequence(value: Any) -> tuple[str, ...]:
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, (list, tuple)):
        candidates = value
    else:
        return ()
    return tuple(dict.fromkeys(str(item).strip() for item in candidates if str(item).strip()))


def _parse_datetime(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _future(seconds: float) -> str:
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
