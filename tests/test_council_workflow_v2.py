from __future__ import annotations

import threading
import unittest
from collections import Counter
from copy import deepcopy
from typing import Any

from finbot.config.ai_sites import DEFAULT_PRODUCT_COUNCIL_TEMPLATE
from finbot.council.conditions import evaluate_condition
from finbot.council.models import CouncilCondition, CouncilTemplate
from finbot.council.orchestrator import CouncilOrchestrator, CouncilTurnRequest
from finbot.council.workflow_engine import WorkflowExecutionEngine, WorkflowNodeContext


class CouncilWorkflowV2Tests(unittest.TestCase):
    def test_condition_dsl_only_reads_structured_fields(self) -> None:
        context = {
            "input": {"symbol": "BTCUSDT", "score": 72.5},
            "current": {"flags": ["fresh", "confirmed"]},
        }

        self.assertTrue(evaluate_condition(CouncilCondition("input.symbol", "eq", "BTCUSDT"), context))
        self.assertTrue(evaluate_condition(CouncilCondition("input.score", "gte", 70), context))
        self.assertTrue(evaluate_condition(CouncilCondition("current.flags", "contains", "fresh"), context))
        self.assertFalse(evaluate_condition(CouncilCondition("input.missing", "exists"), context))

    def test_engine_routes_by_condition_and_joins_any_activation_group(self) -> None:
        template = CouncilTemplate.from_payload(_branching_template())
        executed: list[str] = []

        def execute(context: WorkflowNodeContext) -> dict[str, Any]:
            executed.append(context.node.node_id)
            if context.node.node_type == "router":
                return {"route": context.workflow_input["route"]}
            if context.node.node_type == "agent":
                return {"role_id": context.node.role_id}
            return {"received": sorted(context.incoming_outputs)}

        result = WorkflowExecutionEngine(execute).run(
            run_id="route-bull",
            workflow=template.workflow,
            workflow_input={"route": "bull"},
        )

        self.assertEqual(result["status"], "completed")
        self.assertIn("node_bull", executed)
        self.assertNotIn("node_bear", executed)
        self.assertEqual(result["node_statuses"]["node_bear"], "skipped")
        self.assertEqual(result["outputs"]["node_merge"]["received"], ["node_bull"])

    def test_engine_retries_and_runs_bounded_loop(self) -> None:
        template = CouncilTemplate.from_payload(_loop_template())
        calls: Counter[str] = Counter()

        def execute(context: WorkflowNodeContext) -> dict[str, Any]:
            calls[context.node.node_id] += 1
            if context.node.node_id == "node_bull" and calls[context.node.node_id] == 1:
                raise RuntimeError("transient")
            if context.node.node_id == "node_bear":
                traversals = context.loop_counts.get("edge_revise", 0)
                return {"needs_revision": traversals < 2, "traversal": traversals}
            return {"call": calls[context.node.node_id]}

        result = WorkflowExecutionEngine(execute).run(
            run_id="retry-loop",
            workflow=template.workflow,
            workflow_input={"query": "review"},
        )

        self.assertEqual(result["status"], "completed")
        self.assertEqual(calls["node_bull"], 2)
        self.assertEqual(calls["node_refine"], 3)
        self.assertEqual(calls["node_bear"], 3)
        self.assertEqual(result["loop_counts"], {"edge_revise": 2})
        self.assertLessEqual(result["step_count"], template.workflow.max_steps)

    def test_engine_detects_stalled_loop_and_enforces_resource_budget(self) -> None:
        loop_template = CouncilTemplate.from_payload(_loop_template())

        def stalled(context: WorkflowNodeContext) -> dict[str, Any]:
            if context.node.node_id == "node_bear":
                return {"needs_revision": True}
            return {"ok": True}

        stalled_result = WorkflowExecutionEngine(stalled).run(
            run_id="stalled-loop",
            workflow=loop_template.workflow,
            workflow_input={},
        )

        branching_template = CouncilTemplate.from_payload(_branching_template())

        def expensive(context: WorkflowNodeContext) -> dict[str, Any]:
            if context.node.node_type == "router":
                return {"route": "bull", "usage": {"total_tokens": 100}}
            return {"ok": True}

        budget_result = WorkflowExecutionEngine(expensive).run(
            run_id="budget-limit",
            workflow=branching_template.workflow,
            workflow_input={},
            max_total_tokens=50,
        )

        self.assertEqual(stalled_result["status"], "completed_with_limits")
        self.assertEqual(stalled_result["loop_counts"], {"edge_revise": 1})
        self.assertTrue(any(event["status"] == "loop_stalled" for event in stalled_result["events"]))
        self.assertEqual(budget_result["status"], "failed")
        self.assertIn("max_total_tokens", budget_result["error"])

    def test_human_review_checkpoint_resumes_without_repeating_completed_nodes(self) -> None:
        template = CouncilTemplate.from_payload(_human_review_template())
        calls: Counter[str] = Counter()

        def execute(context: WorkflowNodeContext) -> dict[str, Any]:
            calls[context.node.node_id] += 1
            return {"node_id": context.node.node_id}

        engine = WorkflowExecutionEngine(execute)
        waiting = engine.run(
            run_id="human-review",
            workflow=template.workflow,
            workflow_input={"query": "approve"},
        )
        resumed = engine.run(
            run_id="human-review",
            workflow=template.workflow,
            workflow_input={"query": "approve"},
            resume_state=waiting["checkpoint"],
            resume_node_outputs={"node_review": {"approved": True, "reviewer": "operator"}},
        )

        self.assertEqual(waiting["status"], "waiting")
        self.assertEqual(waiting["pending_node_ids"], ["node_review"])
        self.assertEqual(resumed["status"], "completed")
        self.assertEqual(calls["node_bull"], 1)
        self.assertEqual(resumed["outputs"]["node_review"]["approved"], True)

    def test_moderated_phase_runs_moderator_after_other_participants(self) -> None:
        payload = deepcopy(DEFAULT_PRODUCT_COUNCIL_TEMPLATE)
        payload["workflow"] = CouncilTemplate.from_payload(payload).workflow.to_dict()
        payload["workflow"]["version"] = 2
        payload["workflow"]["max_steps"] = 100
        payload["workflow"]["max_loop_iterations"] = 3
        payload["phases"] = [
            {
                "phase_id": "moderated_review",
                "label": "主持式审查",
                "message_type": "moderated",
                "scheduling_mode": "moderated",
                "moderator_role_id": "risk_controller",
                "instructions": "先由研究角色发言，再由风险角色主持总结。",
            }
        ]
        payload["round_policy"] = {"default_rounds": 1, "min_rounds": 1, "max_rounds": 4}
        template = CouncilTemplate.from_payload(payload)
        requests: dict[str, CouncilTurnRequest] = {}
        lock = threading.Lock()

        def execute(request: CouncilTurnRequest) -> dict[str, Any]:
            with lock:
                requests[request.role.role_id] = request
            return {
                "message_id": f"message-{request.role.role_id}",
                "agent_role": request.role.role_id,
                "status": "completed",
                "content": {"role": request.role.role_id},
            }

        result = CouncilOrchestrator(execute).run(
            council_id="moderated",
            template=template,
            shared_context={},
            rounds=1,
        )

        moderator_request = requests["risk_controller"]
        self.assertEqual(result["status"], "ready_for_synthesis")
        self.assertEqual(
            {message["agent_role"] for message in moderator_request.prior_messages},
            {"bull_researcher", "bear_researcher", "market_structure"},
        )
        self.assertTrue(result["round_summaries"][0]["workflow_layers"][-1]["moderator"])


def _base_template() -> dict[str, Any]:
    return {
        "template_id": "workflow_v2_test",
        "display_name": "Workflow v2 test",
        "roles": [
            {
                "role_id": "bull_role",
                "display_name": "Bull",
                "stance": "bullish",
                "objective": "Find support",
                "order": 10,
                "reasoning_effort": "medium",
            },
            {
                "role_id": "bear_role",
                "display_name": "Bear",
                "stance": "bearish",
                "objective": "Find counter evidence",
                "order": 20,
            },
        ],
        "phases": [
            {
                "phase_id": "analysis_phase",
                "label": "Analysis",
                "message_type": "analysis",
                "scheduling_mode": "round_robin",
                "instructions": "Analyze",
            }
        ],
        "chair": {"role_id": "chair_role", "display_name": "Chair"},
        "round_policy": {"default_rounds": 2, "min_rounds": 1, "max_rounds": 6},
        "cost_tier": "standard",
        "failure_policy": "stop",
    }


def _branching_template() -> dict[str, Any]:
    payload = _base_template()
    payload["workflow"] = {
        "version": 2,
        "max_steps": 40,
        "max_loop_iterations": 3,
        "nodes": [
            _node("input_context", "input", 0, 0),
            _node("node_router", "router", 100, 0, operation="route_candidate"),
            _node("node_bull", "agent", 200, -80, role_id="bull_role"),
            _node("node_bear", "agent", 200, 80, role_id="bear_role"),
            _node("node_merge", "aggregator", 300, 0, operation="merge_views"),
            _node("node_chair", "chair", 400, 0, role_id="chair_role"),
        ],
        "edges": [
            _edge("edge_input", "input_context", "node_router"),
            _edge("edge_bull", "node_router", "node_bull", condition={"field": "current.route", "operator": "eq", "value": "bull"}),
            _edge("edge_bear", "node_router", "node_bear", condition={"field": "current.route", "operator": "eq", "value": "bear"}),
            _edge("edge_merge_bull", "node_bull", "node_merge", activation_group="available_views", activation_mode="any"),
            _edge("edge_merge_bear", "node_bear", "node_merge", activation_group="available_views", activation_mode="any"),
            _edge("edge_chair", "node_merge", "node_chair"),
        ],
    }
    return payload


def _loop_template() -> dict[str, Any]:
    payload = _base_template()
    payload["workflow"] = {
        "version": 2,
        "max_steps": 30,
        "max_loop_iterations": 3,
        "nodes": [
            _node("input_context", "input", 0, 0),
            _node("node_bull", "agent", 100, 0, role_id="bull_role", retry_policy={"max_attempts": 2}),
            _node("node_refine", "deterministic", 200, 0, operation="refine_evidence"),
            _node("node_bear", "agent", 300, 0, role_id="bear_role"),
            _node("node_chair", "chair", 400, 0, role_id="chair_role"),
        ],
        "edges": [
            _edge("edge_input", "input_context", "node_bull"),
            _edge("edge_refine", "node_bull", "node_refine"),
            _edge("edge_bear", "node_refine", "node_bear"),
            _edge("edge_chair", "node_bear", "node_chair"),
            _edge(
                "edge_revise",
                "node_bear",
                "node_refine",
                loop=True,
                max_traversals=2,
                condition={"field": "current.needs_revision", "operator": "truthy"},
            ),
        ],
    }
    return payload


def _human_review_template() -> dict[str, Any]:
    payload = _base_template()
    payload["workflow"] = {
        "version": 2,
        "nodes": [
            _node("input_context", "input", 0, 0),
            _node("node_bull", "agent", 100, 0, role_id="bull_role"),
            _node("node_review", "human_review", 200, 0, operation="operator_approval"),
            _node("node_bear", "agent", 300, 0, role_id="bear_role"),
            _node("node_chair", "chair", 400, 0, role_id="chair_role"),
        ],
        "edges": [
            _edge("edge_bull", "input_context", "node_bull"),
            _edge("edge_review", "node_bull", "node_review"),
            _edge("edge_bear", "node_review", "node_bear"),
            _edge("edge_chair", "node_bear", "node_chair"),
        ],
    }
    return payload


def _node(
    node_id: str,
    node_type: str,
    x: float,
    y: float,
    *,
    role_id: str | None = None,
    operation: str | None = None,
    retry_policy: dict[str, Any] | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "node_id": node_id,
        "node_type": node_type,
        "role_id": role_id,
        "position": {"x": x, "y": y},
    }
    if operation:
        payload["operation"] = operation
    if retry_policy:
        payload["retry_policy"] = retry_policy
    return payload


def _edge(
    edge_id: str,
    source: str,
    target: str,
    **extra: Any,
) -> dict[str, Any]:
    return {"edge_id": edge_id, "source_node_id": source, "target_node_id": target, **extra}


if __name__ == "__main__":
    unittest.main()
