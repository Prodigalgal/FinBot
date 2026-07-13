from __future__ import annotations

from typing import Any


RESULT_FIELDS = (
    "latest_recommendations",
    "latest_ai_debates",
    "latest_ai_decisions",
    "latest_universe",
    "latest_evaluation",
    "latest_portfolio_risk",
    "latest_ai_governance",
)
STEP_OUTPUT_SCALARS = (
    "status",
    "run_id",
    "report_id",
    "debate_id",
    "council_id",
    "execution_run_id",
    "risk_report_id",
    "governance_report_id",
)
STEP_OUTPUT_COLLECTIONS = (
    "results",
    "items",
    "candidates",
    "ai_decisions",
    "recommended_products",
)


def operations_update_payload(
    snapshot: dict[str, Any],
    previous_snapshot: dict[str, Any],
) -> dict[str, Any]:
    status = _mapping(snapshot.get("status"))
    autonomous = _mapping(snapshot.get("autonomous"))
    previous_autonomous = _mapping(previous_snapshot.get("autonomous"))
    result_changed = autonomous.get("latest_result_loop_run_id") != previous_autonomous.get(
        "latest_result_loop_run_id"
    )
    autonomous_update = {
        key: autonomous.get(key)
        for key in (
            "status",
            "generated_at",
            "scheduler",
            "config",
            "latest_result_loop_run_id",
            "latest_decision_readiness",
            "paper_execution",
            "policy",
        )
    }
    autonomous_update["worker"] = _compact_worker(_mapping(autonomous.get("worker")))
    autonomous_update["recent_runs"] = [
        _compact_run(run)
        for run in _records(autonomous.get("recent_runs"))[:3]
    ]
    if result_changed:
        autonomous_update.update({key: autonomous.get(key) for key in RESULT_FIELDS})
    return {
        "partial": True,
        "status": _compact_status(status),
        "autonomous": autonomous_update,
        "jobs": [_compact_job(job) for job in _records(snapshot.get("jobs"))[:10]],
    }


def _compact_status(status: dict[str, Any]) -> dict[str, Any]:
    payload = {
        key: status.get(key)
        for key in (
            "status",
            "service",
            "generated_at",
            "counts",
            "source_statuses",
            "autonomous_scheduler",
            "latest_advisory_report",
            "policy",
        )
    }
    payload["latest_autonomous_loop"] = _compact_optional_run(status.get("latest_autonomous_loop"))
    payload["latest_pipeline_run"] = _compact_pipeline_run(status.get("latest_pipeline_run"))
    return payload


def _compact_worker(worker: dict[str, Any]) -> dict[str, Any]:
    active_workers = [
        row
        for row in _records(worker.get("workers"))
        if row.get("active") is True
    ]
    return {
        "queue": worker.get("queue") or {},
        "workers": active_workers,
        "leases": [],
        "scheduler": worker.get("scheduler"),
        "recent_requests": [
            {
                **{
                    key: request.get(key)
                    for key in (
                        "request_id",
                        "trigger_type",
                        "status",
                        "requested_at",
                        "started_at",
                        "finished_at",
                        "worker_id",
                        "loop_run_id",
                        "attempt",
                        "error",
                    )
                },
                "payload": {},
                "result": {},
            }
            for request in _records(worker.get("recent_requests"))[:6]
        ],
    }


def _compact_optional_run(value: Any) -> dict[str, Any] | None:
    return _compact_run(value) if isinstance(value, dict) else None


def _compact_run(run: dict[str, Any]) -> dict[str, Any]:
    payload = {
        key: run.get(key)
        for key in (
            "loop_run_id",
            "status",
            "trigger_type",
            "summary",
            "decision_readiness",
            "started_at",
            "finished_at",
            "error",
        )
    }
    payload["config"] = {}
    payload["steps"] = [_compact_step(step) for step in _records(run.get("steps"))]
    return payload


def _compact_pipeline_run(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    payload = {
        key: value.get(key)
        for key in ("run_id", "profile", "status", "started_at", "finished_at", "error", "summary")
    }
    payload["steps"] = [_compact_step(step) for step in _records(value.get("steps"))]
    return payload


def _compact_step(step: dict[str, Any]) -> dict[str, Any]:
    output = _mapping(step.get("output"))
    output_summary = {
        key: output.get(key)
        for key in STEP_OUTPUT_SCALARS
        if isinstance(output.get(key), (str, int, float, bool, type(None)))
    }
    for key in STEP_OUTPUT_COLLECTIONS:
        value = output.get(key)
        if isinstance(value, dict) and isinstance(value.get("count"), (int, float)):
            output_summary[key] = {"count": value["count"]}
        elif isinstance(value, list):
            output_summary[key] = {"count": len(value)}
    return {
        key: step.get(key)
        for key in (
            "step_id",
            "step_name",
            "status",
            "attempt",
            "started_at",
            "finished_at",
            "duration_ms",
            "error",
        )
    } | {"input": {}, "output": output_summary}


def _compact_job(job: dict[str, Any]) -> dict[str, Any]:
    return {
        key: job.get(key)
        for key in ("job_id", "kind", "status", "created_at", "started_at", "finished_at", "error")
    } | {"request": {}, "result": None}


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _records(value: Any) -> list[dict[str, Any]]:
    return [item for item in value or [] if isinstance(item, dict)] if isinstance(value, list) else []
