from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from typing import Any

from finbot.autonomous.worker import AutonomousRequestQueue
from finbot.storage.sqlite_store import SQLiteStore


REPLAY_CONFIG_FIELDS = (
    "profile",
    "continue_on_error",
    "run_research_pipeline",
    "run_ingestion",
    "max_initial_jobs",
    "run_ai_compression",
    "ai_compression_dry_run",
    "run_followups",
    "followups_dry_run",
    "max_events",
    "include_background_council",
    "run_instrument_catalog",
    "universe_mode",
    "universe_quote_assets",
    "universe_max_instruments",
    "universe_min_turnover_24h",
    "universe_max_spread_pct",
    "run_operator_workbench",
    "run_ai_debate",
    "ai_debate_rounds",
    "council_template_id",
    "ai_debate_max_candidates",
    "ai_trade_min_confidence",
    "ai_trade_require_research_confirmation",
    "symbols",
    "providers",
    "intervals",
    "candle_limit",
    "recommendation_min_confidence",
    "max_recommendations",
)


class ResearchHistoryService:
    def __init__(self, store: SQLiteStore):
        self.store = store
        self.store.init_schema()

    def list_runs(self, *, limit: int = 50, status: str | None = None) -> dict[str, Any]:
        rows = self.store.list_autonomous_loop_runs(limit=max(1, min(limit * 3, 300)))
        items = []
        for row in rows:
            if status and str(row["status"]) != status:
                continue
            items.append(self._run_summary(row))
            if len(items) >= limit:
                break
        return {"status": "ok", "count": len(items), "items": items}

    def get_run(self, loop_run_id: str) -> dict[str, Any]:
        row = self.store.get_autonomous_loop_run(loop_run_id)
        if row is None:
            raise LookupError(f"未找到自动研究运行：{loop_run_id}")
        summary = self._run_summary(row)
        steps = [_loop_step_payload(step) for step in self.store.latest_autonomous_loop_steps(loop_run_id)]
        pipeline_row = self.store.get_research_pipeline_run_by_trigger(f"autonomous:{loop_run_id}")
        pipeline = _pipeline_payload(self.store, pipeline_row)
        decisions = [_decision_payload(item) for item in self.store.list_ai_trade_decisions(loop_run_id=loop_run_id)]
        reviews = {
            str(item["decision_id"]): _review_payload(item)
            for item in self.store.list_decision_reviews(loop_run_id=loop_run_id, limit=500)
        }
        risk = _report_payload(self.store.latest_portfolio_risk_report(loop_run_id))
        governance = _report_payload(self.store.latest_ai_governance_report(loop_run_id))
        evaluations = [
            _evaluation_payload(item)
            for item in self.store.list_recommendation_evaluation_runs(limit=200)
            if item["loop_run_id"] == loop_run_id
        ]
        paper_executions = [_paper_execution_payload(item) for item in self.store.list_paper_executions(loop_run_id=loop_run_id)]
        replays = [_replay_payload(item) for item in self.store.list_run_replays(loop_run_id)]
        return {
            **summary,
            "steps": steps,
            "research_pipeline": pipeline,
            "decisions": [
                {**decision, "review": reviews.get(str(decision["decision_id"]), {"status": "pending", "version": 0})}
                for decision in decisions
            ],
            "portfolio_risk": risk,
            "ai_governance": governance,
            "evaluations": evaluations,
            "paper_executions": paper_executions,
            "replays": replays,
        }

    def compare_runs(self, left_loop_run_id: str, right_loop_run_id: str) -> dict[str, Any]:
        if left_loop_run_id == right_loop_run_id:
            raise ValueError("请选择两个不同的运行进行对比")
        left = self.get_run(left_loop_run_id)
        right = self.get_run(right_loop_run_id)
        fields = (
            ("status", left.get("run_status"), right.get("run_status")),
            ("trigger_type", left.get("trigger_type"), right.get("trigger_type")),
            ("decision_readiness", (left.get("decision_readiness") or {}).get("status"), (right.get("decision_readiness") or {}).get("status")),
            ("total_duration_ms", left.get("total_duration_ms"), right.get("total_duration_ms")),
            ("decision_count", left.get("decision_count"), right.get("decision_count")),
            ("directional_count", left.get("directional_count"), right.get("directional_count")),
            ("estimated_cost_usd", _cost(left), _cost(right)),
        )
        metric_changes = [
            {"field": field, "left": left_value, "right": right_value, "changed": left_value != right_value}
            for field, left_value, right_value in fields
        ]
        left_decisions = {_decision_key(item): item for item in left["decisions"]}
        right_decisions = {_decision_key(item): item for item in right["decisions"]}
        decision_changes = []
        for key in sorted(set(left_decisions) | set(right_decisions)):
            left_item = left_decisions.get(key)
            right_item = right_decisions.get(key)
            left_projection = _decision_projection(left_item)
            right_projection = _decision_projection(right_item)
            decision_changes.append(
                {
                    "key": key,
                    "left": left_projection,
                    "right": right_projection,
                    "changed": left_projection != right_projection,
                }
            )
        return {
            "status": "ok",
            "left_loop_run_id": left_loop_run_id,
            "right_loop_run_id": right_loop_run_id,
            "metric_changes": metric_changes,
            "decision_changes": decision_changes,
        }

    def replay_run(self, loop_run_id: str) -> dict[str, Any]:
        source = self.get_run(loop_run_id)
        if source["run_status"] in {"running", "queued"}:
            raise ValueError("运行尚未结束，不能 replay")
        source_config = source.get("config") if isinstance(source.get("config"), dict) else {}
        replay_config = {
            key: source_config[key]
            for key in REPLAY_CONFIG_FIELDS
            if key in source_config
        }
        request = AutonomousRequestQueue(self.store).enqueue(
            trigger_type="replay",
            payload={
                "mode": "replay",
                "source_loop_run_id": loop_run_id,
                "replay_config": replay_config,
                "requested_by": "history-api",
            },
        )
        now = _now()
        replay = {
            "replay_id": _stable_id("run-replay", loop_run_id, request["request_id"]),
            "source_loop_run_id": loop_run_id,
            "source_pipeline_run_id": (source.get("research_pipeline") or {}).get("run_id"),
            "request_id": request["request_id"],
            "mode": "replay",
            "status": "queued",
            "config": replay_config,
            "created_at": now,
            "updated_at": now,
        }
        self.store.insert_run_replay(replay)
        return {"status": "accepted", "replay": replay, "request": request}

    def resume_request(self, loop_run_id: str, from_step: str | None = None) -> dict[str, Any]:
        detail = self.get_run(loop_run_id)
        pipeline = detail.get("research_pipeline")
        if not isinstance(pipeline, dict):
            raise ValueError("该运行没有可续跑的 research pipeline")
        failed_steps = [step for step in pipeline.get("steps", []) if step.get("status") == "failed"]
        selected_step = str(from_step or (failed_steps[0].get("step_name") if failed_steps else "")).strip()
        if not selected_step:
            raise ValueError("该 research pipeline 没有失败步骤")
        known_steps = {str(step.get("step_name")) for step in pipeline.get("steps", [])}
        if selected_step not in known_steps:
            raise ValueError(f"未知 research pipeline 步骤：{selected_step}")
        return {
            "resume_run_id": pipeline["run_id"],
            "from_step": selected_step,
            "dry_run": False,
            "run_ingestion": True,
            "run_ai_compression": True,
            "run_followups": True,
            "followups_dry_run": False,
            "profile": "history-resume",
        }

    def _run_summary(self, row: Any) -> dict[str, Any]:
        loop_run_id = str(row["loop_run_id"])
        summary = _loads(row["summary_json"], {})
        config = _loads(row["config_json"], {})
        decisions = self.store.list_ai_trade_decisions(loop_run_id=loop_run_id)
        pipeline_row = self.store.get_research_pipeline_run_by_trigger(f"autonomous:{loop_run_id}")
        return {
            "loop_run_id": loop_run_id,
            "run_status": row["status"],
            "trigger_type": row["trigger_type"],
            "started_at": row["started_at"],
            "finished_at": row["finished_at"],
            "error": row["error"],
            "config": config,
            "summary": summary,
            "decision_readiness": summary.get("decision_readiness") if isinstance(summary, dict) else None,
            "total_duration_ms": (summary.get("total_duration_ms") if isinstance(summary, dict) else None),
            "decision_count": len(decisions),
            "directional_count": sum(1 for item in decisions if str(item["action"]).upper() in {"BUY", "SELL"}),
            "research_pipeline_status": pipeline_row["status"] if pipeline_row is not None else None,
        }


def _pipeline_payload(store: SQLiteStore, row: Any | None) -> dict[str, Any] | None:
    if row is None:
        return None
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
        "steps": [_pipeline_step_payload(item) for item in store.latest_research_pipeline_steps(row["run_id"])],
    }


def _loop_step_payload(row: Any) -> dict[str, Any]:
    return {
        "step_id": row["step_id"],
        "step_name": row["step_name"],
        "status": row["status"],
        "attempt": row["attempt"],
        "duration_ms": row["duration_ms"],
        "error": row["error"],
    }


def _pipeline_step_payload(row: Any) -> dict[str, Any]:
    return {
        "step_id": row["step_id"],
        "step_name": row["step_name"],
        "status": row["status"],
        "attempt": row["attempt"],
        "duration_ms": row["duration_ms"],
        "error": row["error"],
    }


def _decision_payload(row: Any) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    if not isinstance(payload, dict):
        payload = {}
    for key in (
        "decision_id", "loop_run_id", "provider", "market_type", "symbol", "normalized_symbol",
        "action", "status", "confidence", "score", "entry_reference", "target_price",
        "invalidation_price", "created_at",
    ):
        payload[key] = row[key]
    return payload


def _review_payload(row: Any) -> dict[str, Any]:
    return {
        "review_id": row["review_id"],
        "status": row["status"],
        "version": row["version"],
        "reviewer_id": row["reviewer_id"],
        "note": row["note"],
        "updated_at": row["updated_at"],
    }


def _report_payload(row: Any | None) -> dict[str, Any]:
    if row is None:
        return {}
    payload = _loads(row["payload_json"], {})
    return payload if isinstance(payload, dict) else {}


def _evaluation_payload(row: Any) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    return payload if isinstance(payload, dict) else {}


def _paper_execution_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["request"] = _loads(payload.pop("request_json", "{}"), {})
    payload["response"] = _loads(payload.pop("response_json", "{}"), {})
    return payload


def _replay_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["config"] = _loads(payload.pop("config_json", "{}"), {})
    return payload


def _decision_key(item: dict[str, Any]) -> str:
    return ":".join(
        str(item.get(key) or "-")
        for key in ("provider", "market_type", "normalized_symbol")
    )


def _decision_projection(item: dict[str, Any] | None) -> dict[str, Any] | None:
    if not item:
        return None
    return {
        "action": item.get("action"),
        "status": item.get("status"),
        "confidence": item.get("confidence"),
        "score": item.get("score"),
        "review_status": (item.get("review") or {}).get("status"),
    }


def _cost(run: dict[str, Any]) -> Any:
    return ((run.get("ai_governance") or {}).get("summary") or {}).get("estimated_cost_usd")


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _stable_id(*parts: Any) -> str:
    return hashlib.sha256(":".join(str(part) for part in parts).encode("utf-8")).hexdigest()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
