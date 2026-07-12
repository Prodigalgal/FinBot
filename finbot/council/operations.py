from __future__ import annotations

from collections.abc import Callable
from typing import Any

from finbot.council.workflow_engine import WorkflowNodeContext


ExternalNodeExecutor = Callable[[WorkflowNodeContext], dict[str, Any]]


class WorkflowOperationRegistry:
    SUPPORTED_OPERATIONS = {
        "market_snapshot",
        "research_router",
        "evidence_quality",
        "research_gap_gate",
        "evidence_followup",
        "evidence_recheck",
        "investment_committee_review",
        "event_router",
        "position_snapshot",
        "position_review_merge",
    }

    def __init__(self, external_node_executor: ExternalNodeExecutor | None = None) -> None:
        self.external_node_executor = external_node_executor

    def execute(self, context: WorkflowNodeContext) -> dict[str, Any]:
        node = context.node
        if node.node_type in {"agent", "chair"} or (node.node_type == "aggregator" and node.role_id):
            if self.external_node_executor:
                return self.external_node_executor(context)
            return self._dry_run_role(context)
        operation = node.operation
        if operation not in self.SUPPORTED_OPERATIONS:
            raise ValueError(f"工作流 operation 未注册：{operation}")
        handler = getattr(self, f"_operation_{operation}")
        return handler(context)

    @staticmethod
    def _dry_run_role(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "dry_run": True,
            "node_id": context.node.node_id,
            "role_id": context.node.role_id,
            "input_node_ids": sorted(context.incoming_outputs),
            "evidence_refs": _evidence_refs(context),
            "summary": "节点契约、依赖和上下文已验证；dry-run 未发送外部 AI 请求。",
            "hidden_reasoning_persisted": False,
        }

    @staticmethod
    def _operation_market_snapshot(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "market_available": bool(context.workflow_input.get("market_data") or context.workflow_input.get("symbol")),
            "symbol": context.workflow_input.get("symbol"),
            "market_type": context.workflow_input.get("market_type"),
        }

    @staticmethod
    def _operation_research_router(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "route": context.workflow_input.get("product_type") or "general_product",
            "evidence_status": context.workflow_input.get("evidence_status") or "unknown",
        }

    @staticmethod
    def _operation_evidence_quality(context: WorkflowNodeContext) -> dict[str, Any]:
        references = _evidence_refs(context)
        status = str(context.workflow_input.get("evidence_status") or "unknown")
        needs_more = status in {"unknown", "empty", "needs-followup", "unconfirmed"} or not references
        return {
            "evidence_status": status,
            "evidence_count": len(references),
            "needs_more": needs_more,
            "evidence_refs": references,
        }

    @staticmethod
    def _operation_research_gap_gate(context: WorkflowNodeContext) -> dict[str, Any]:
        source = _first_incoming(context)
        return {
            "needs_more": bool(source.get("needs_more", True)),
            "gap_reason": "证据不足或状态未确认" if source.get("needs_more", True) else None,
        }

    @staticmethod
    def _operation_evidence_followup(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "followup_planned": True,
            "external_write_sent": False,
            "new_evidence_count": 0,
            "needs_more": True,
        }

    @staticmethod
    def _operation_evidence_recheck(context: WorkflowNodeContext) -> dict[str, Any]:
        traversals = context.loop_counts.get("edge_research_loop", 0)
        return {
            "needs_more": traversals < 1,
            "recheck_iteration": context.iteration,
            "new_evidence_count": sum(
                int(output.get("new_evidence_count") or 0)
                for output in context.incoming_outputs.values()
            ),
        }

    @staticmethod
    def _operation_investment_committee_review(context: WorkflowNodeContext) -> dict[str, Any]:
        return {"status": "waiting", "output": {"review_required": True}}

    @staticmethod
    def _operation_event_router(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "route": "event_analysis",
            "event_type": context.workflow_input.get("event_type") or "unspecified",
        }

    @staticmethod
    def _operation_position_snapshot(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "decision_id": context.workflow_input.get("decision_id"),
            "symbol": context.workflow_input.get("symbol"),
            "position_available": bool(context.workflow_input.get("position") or context.workflow_input.get("decision_id")),
        }

    @staticmethod
    def _operation_position_review_merge(context: WorkflowNodeContext) -> dict[str, Any]:
        return {
            "review_count": len(context.incoming_outputs),
            "review_node_ids": sorted(context.incoming_outputs),
            "evidence_refs": _evidence_refs(context),
        }


def _first_incoming(context: WorkflowNodeContext) -> dict[str, Any]:
    return next(iter(context.incoming_outputs.values()), {})


def _evidence_refs(context: WorkflowNodeContext) -> list[str]:
    values: list[str] = []
    raw = context.workflow_input.get("evidence_refs")
    if isinstance(raw, (list, tuple)):
        values.extend(str(item) for item in raw if str(item).strip())
    for output in context.incoming_outputs.values():
        references = output.get("evidence_refs")
        if isinstance(references, (list, tuple)):
            values.extend(str(item) for item in references if str(item).strip())
    return list(dict.fromkeys(values))[:100]
