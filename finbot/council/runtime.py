from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from finbot.config.ai_sites import AISitesConfigStore
from finbot.council.director import ResearchDirector
from finbot.council.models import CouncilTemplate
from finbot.council.operations import ExternalNodeExecutor, WorkflowOperationRegistry
from finbot.council.workflow_engine import WorkflowExecutionEngine
from finbot.storage.sqlite_store import SQLiteStore


class WorkflowRunService:
    def __init__(
        self,
        store: SQLiteStore,
        ai_store: AISitesConfigStore,
        *,
        director: ResearchDirector | None = None,
        external_node_executor: ExternalNodeExecutor | None = None,
    ) -> None:
        self.store = store
        self.ai_store = ai_store
        self.director = director or ResearchDirector()
        self.external_node_executor = external_node_executor
        self.store.init_schema()

    def plan(self, request: dict[str, Any]) -> dict[str, Any]:
        return self.director.plan(_sanitize(request), self.ai_store.council_templates())

    def run(
        self,
        request: dict[str, Any],
        *,
        dry_run: bool = True,
        workflow_version_id: str | None = None,
    ) -> dict[str, Any]:
        if not dry_run and self.external_node_executor is None:
            raise ValueError("live 工作流运行需要受控的外部节点执行器")
        safe_request = _sanitize(request)
        templates = list(self.ai_store.council_templates())
        version_template = self._version_template(workflow_version_id) if workflow_version_id else None
        if version_template is not None:
            templates = [item for item in templates if item.template_id != version_template.template_id]
            templates.append(version_template)
        plan = self.director.plan(safe_request, tuple(templates))
        template = version_template or self.ai_store.council_template(str(plan["template_id"]))
        if template.template_id != str(plan["template_id"]):
            raise ValueError("工作流版本与 Director 选择的 template_id 不匹配")
        now = _now()
        workflow_run_id = uuid4().hex
        workflow_run = {
            "workflow_run_id": workflow_run_id,
            "template_id": template.template_id,
            "workflow_version_id": workflow_version_id,
            "trigger_type": str(plan["trigger_type"]),
            "mode": "dry_run" if dry_run else "live",
            "status": "planned",
            "depth": str(plan["depth"]),
            "cost_tier": str(plan["cost_tier"]),
            "request": safe_request,
            "template_snapshot": template.to_dict(),
            "plan": plan,
            "result": {},
            "error": None,
            "version": 0,
            "created_at": now,
            "started_at": None,
            "updated_at": now,
            "finished_at": None,
        }
        self.store.insert_workflow_run(workflow_run)
        self._insert_ledger(workflow_run_id, "task", 1, _task_ledger(plan))
        self.store.update_workflow_run(
            workflow_run_id,
            status="running",
            result={},
            error=None,
            started_at=now,
            updated_at=now,
            expected_version=0,
        )
        result = self._execute(
            workflow_run_id=workflow_run_id,
            template=template,
            workflow_input=safe_request,
            dry_run=dry_run,
            budget_policy=plan.get("budget_policy") if isinstance(plan.get("budget_policy"), dict) else {},
        )
        return self._finalize(workflow_run_id, plan, result)

    def _version_template(self, workflow_version_id: str) -> CouncilTemplate:
        row = self.store.get_workflow_version(workflow_version_id)
        if row is None:
            raise ValueError(f"未找到工作流版本：{workflow_version_id}")
        return CouncilTemplate.from_payload(_loads(row["content_json"], {}))

    def resume(
        self,
        workflow_run_id: str,
        *,
        node_outputs: dict[str, dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        row = self.store.get_workflow_run(workflow_run_id)
        if row is None:
            raise LookupError(f"未找到工作流运行：{workflow_run_id}")
        if row["status"] not in {"waiting_human", "failed", "partial"}:
            raise ValueError(f"当前工作流状态不允许续跑：{row['status']}")
        template = CouncilTemplate.from_payload(_loads(row["template_snapshot_json"], {}))
        request = _loads(row["request_json"], {})
        plan = _loads(row["plan_json"], {})
        previous_result = _loads(row["result_json"], {})
        resume_state = previous_result.get("checkpoint") if isinstance(previous_result.get("checkpoint"), dict) else {}
        now = _now()
        self.store.update_workflow_run(
            workflow_run_id,
            status="running",
            result=previous_result,
            error=None,
            started_at=now,
            updated_at=now,
            expected_version=int(row["version"]),
        )
        result = self._execute(
            workflow_run_id=workflow_run_id,
            template=template,
            workflow_input=request,
            dry_run=row["mode"] == "dry_run",
            resume_state=resume_state,
            resume_node_outputs=_sanitize(node_outputs or {}),
            budget_policy=plan.get("budget_policy") if isinstance(plan.get("budget_policy"), dict) else {},
        )
        return self._finalize(workflow_run_id, plan, result)

    def get(self, workflow_run_id: str) -> dict[str, Any]:
        row = self.store.get_workflow_run(workflow_run_id)
        if row is None:
            raise LookupError(f"未找到工作流运行：{workflow_run_id}")
        return self._detail(row)

    def list(
        self,
        *,
        template_id: str | None = None,
        status: str | None = None,
        limit: int = 100,
    ) -> dict[str, Any]:
        runs = [
            self._summary(row)
            for row in self.store.list_workflow_runs(
                template_id=template_id,
                status=status,
                limit=limit,
            )
        ]
        return {"status": "ok", "count": len(runs), "runs": runs}

    def _execute(
        self,
        *,
        workflow_run_id: str,
        template: CouncilTemplate,
        workflow_input: dict[str, Any],
        dry_run: bool,
        resume_state: dict[str, Any] | None = None,
        resume_node_outputs: dict[str, dict[str, Any]] | None = None,
        budget_policy: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        registry = WorkflowOperationRegistry(
            None if dry_run else self.external_node_executor
        )
        engine = WorkflowExecutionEngine(
            registry.execute,
            checkpoint_hook=lambda checkpoint: self._persist_checkpoint(
                workflow_run_id,
                template,
                checkpoint,
            ),
        )
        result = engine.run(
            run_id=workflow_run_id,
            workflow=template.workflow,
            workflow_input=workflow_input,
            failure_policy=template.failure_policy,
            resume_state=resume_state,
            resume_node_outputs=resume_node_outputs,
            max_duration_seconds=_float((budget_policy or {}).get("max_duration_seconds"), 0.0) or None,
            max_total_tokens=_integer((budget_policy or {}).get("max_total_tokens"), 0) or None,
            max_cost_usd=(
                _float((budget_policy or {}).get("max_cost_usd"), 0.0)
                if (budget_policy or {}).get("max_cost_usd") is not None
                else None
            ),
        )
        return _sanitize(result)

    def _finalize(
        self,
        workflow_run_id: str,
        plan: dict[str, Any],
        result: dict[str, Any],
    ) -> dict[str, Any]:
        engine_status = str(result.get("status") or "failed")
        status = {
            "completed": "completed",
            "completed_with_limits": "partial",
            "partial": "partial",
            "waiting": "waiting_human",
            "replan_required": "partial",
            "failed": "failed",
        }.get(engine_status, "failed")
        if engine_status == "replan_required":
            plan = self.director.replan(
                plan,
                reason=str(result.get("error") or "节点失败触发重规划"),
                failed_node_id=_failed_node_id(result),
            )
            self._insert_ledger(workflow_run_id, "task", 2, _task_ledger(plan))
        progress = _progress_ledger(result, status)
        existing_progress = [
            row for row in self.store.list_workflow_ledgers(workflow_run_id)
            if row["ledger_type"] == "progress"
        ]
        self._insert_ledger(workflow_run_id, "progress", len(existing_progress) + 1, progress)
        now = _now()
        current = self.store.get_workflow_run(workflow_run_id)
        if current is None:
            raise RuntimeError("工作流运行在完成前丢失")
        self.store.update_workflow_run(
            workflow_run_id,
            status=status,
            result=result,
            error=str(result.get("error") or "") or None,
            updated_at=now,
            finished_at=now if status in {"completed", "partial", "failed", "cancelled"} else None,
            expected_version=int(current["version"]),
        )
        return self.get(workflow_run_id)

    def _persist_checkpoint(
        self,
        workflow_run_id: str,
        template: CouncilTemplate,
        checkpoint: dict[str, Any],
    ) -> None:
        statuses = checkpoint.get("node_statuses") if isinstance(checkpoint.get("node_statuses"), dict) else {}
        outputs = checkpoint.get("outputs") if isinstance(checkpoint.get("outputs"), dict) else {}
        attempts = checkpoint.get("attempt_counts") if isinstance(checkpoint.get("attempt_counts"), dict) else {}
        iterations = checkpoint.get("node_iterations") if isinstance(checkpoint.get("node_iterations"), dict) else {}
        events = checkpoint.get("events") if isinstance(checkpoint.get("events"), list) else []
        now = _now()
        nodes = {node.node_id: node for node in template.workflow.nodes}
        for node_id, raw_status in statuses.items():
            node = nodes.get(str(node_id))
            if node is None:
                continue
            status = "waiting_human" if raw_status == "waiting" else str(raw_status)
            iteration = max(0, _integer(iterations.get(node_id), 0))
            error = _latest_node_error(events, str(node_id))
            completed_at = now if status in {"completed", "skipped", "skipped_phase", "waiting_human", "failed"} else None
            self.store.upsert_workflow_node_checkpoint(
                {
                    "checkpoint_id": _stable_id(
                        "workflow-checkpoint",
                        workflow_run_id,
                        node_id,
                        checkpoint.get("phase_id") or "",
                        iteration,
                    ),
                    "workflow_run_id": workflow_run_id,
                    "node_id": node_id,
                    "phase_id": checkpoint.get("phase_id") or "",
                    "iteration": iteration,
                    "node_type": node.node_type,
                    "operation": node.operation,
                    "status": status,
                    "attempt": _integer(attempts.get(node_id), 0),
                    "output": _sanitize(outputs.get(node_id) if isinstance(outputs.get(node_id), dict) else {}),
                    "error": error,
                    "created_at": now,
                    "updated_at": now,
                    "completed_at": completed_at,
                }
            )

    def _insert_ledger(
        self,
        workflow_run_id: str,
        ledger_type: str,
        revision: int,
        payload: dict[str, Any],
    ) -> None:
        self.store.insert_workflow_ledger(
            {
                "ledger_id": _stable_id("workflow-ledger", workflow_run_id, ledger_type, revision),
                "workflow_run_id": workflow_run_id,
                "ledger_type": ledger_type,
                "revision": revision,
                "payload": _sanitize(payload),
                "created_at": _now(),
            }
        )

    def _detail(self, row: Any) -> dict[str, Any]:
        payload = self._summary(row)
        payload.update(
            {
                "request": _loads(row["request_json"], {}),
                "template_snapshot": _loads(row["template_snapshot_json"], {}),
                "plan": _loads(row["plan_json"], {}),
                "result": _loads(row["result_json"], {}),
                "ledgers": [_ledger_payload(item) for item in self.store.list_workflow_ledgers(row["workflow_run_id"])],
                "checkpoints": [
                    _checkpoint_payload(item)
                    for item in self.store.list_workflow_node_checkpoints(row["workflow_run_id"])
                ],
            }
        )
        return payload

    @staticmethod
    def _summary(row: Any) -> dict[str, Any]:
        return {
            "workflow_run_id": row["workflow_run_id"],
            "template_id": row["template_id"],
            "workflow_version_id": row["workflow_version_id"],
            "trigger_type": row["trigger_type"],
            "mode": row["mode"],
            "status": row["status"],
            "depth": row["depth"],
            "cost_tier": row["cost_tier"],
            "error": row["error"],
            "version": row["version"],
            "created_at": row["created_at"],
            "started_at": row["started_at"],
            "updated_at": row["updated_at"],
            "finished_at": row["finished_at"],
        }


def _task_ledger(plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "objective": plan.get("objective"),
        "facts": plan.get("facts", []),
        "assumptions": plan.get("assumptions", []),
        "gaps": plan.get("gaps", []),
        "template_id": plan.get("template_id"),
        "depth": plan.get("depth"),
        "rounds": plan.get("rounds"),
        "revisions": plan.get("revisions", []),
    }


def _progress_ledger(result: dict[str, Any], status: str) -> dict[str, Any]:
    events = result.get("events") if isinstance(result.get("events"), list) else []
    return {
        "status": status,
        "step_count": result.get("step_count"),
        "node_statuses": result.get("node_statuses", {}),
        "attempt_counts": result.get("attempt_counts", {}),
        "loop_counts": result.get("loop_counts", {}),
        "limit_reached": result.get("limit_reached", False),
        "pending_node_ids": result.get("pending_node_ids", []),
        "errors": [
            event for event in events
            if isinstance(event, dict) and event.get("status") in {"attempt_failed", "failed", "loop_limit_reached", "loop_stalled"}
        ][-20:],
    }


def _ledger_payload(row: Any) -> dict[str, Any]:
    return {
        "ledger_id": row["ledger_id"],
        "ledger_type": row["ledger_type"],
        "revision": row["revision"],
        "payload": _loads(row["payload_json"], {}),
        "created_at": row["created_at"],
    }


def _checkpoint_payload(row: Any) -> dict[str, Any]:
    return {
        "checkpoint_id": row["checkpoint_id"],
        "node_id": row["node_id"],
        "phase_id": row["phase_id"],
        "iteration": row["iteration"],
        "node_type": row["node_type"],
        "operation": row["operation"],
        "status": row["status"],
        "attempt": row["attempt"],
        "output": _loads(row["output_json"], {}),
        "error": row["error"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "completed_at": row["completed_at"],
    }


def _failed_node_id(result: dict[str, Any]) -> str | None:
    statuses = result.get("node_statuses") if isinstance(result.get("node_statuses"), dict) else {}
    return next((str(node_id) for node_id, status in statuses.items() if status == "failed"), None)


def _latest_node_error(events: list[Any], node_id: str) -> str | None:
    for event in reversed(events):
        if not isinstance(event, dict) or str(event.get("node_id")) != node_id:
            continue
        detail = event.get("detail") if isinstance(event.get("detail"), dict) else {}
        if detail.get("error"):
            return str(detail["error"])[:500]
    return None


def _sanitize(value: Any, key: str = "") -> Any:
    lowered = key.lower()
    if any(marker in lowered for marker in ("api_key", "secret", "password", "authorization", "access_token")):
        return "***"
    if lowered in {"chain_of_thought", "hidden_reasoning", "thinking_trace"}:
        return "[not persisted]"
    if isinstance(value, dict):
        return {str(item_key): _sanitize(item, str(item_key)) for item_key, item in list(value.items())[:500]}
    if isinstance(value, (list, tuple)):
        return [_sanitize(item, key) for item in list(value)[:1000]]
    if isinstance(value, str):
        return value[:20_000]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return str(value)[:20_000]


def _stable_id(*parts: Any) -> str:
    text = json.dumps(parts, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":"))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:32]


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _integer(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
