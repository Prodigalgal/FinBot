from __future__ import annotations

import time
import hashlib
import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.council.conditions import evaluate_condition
from finbot.council.models import CouncilWorkflow, CouncilWorkflowEdge, CouncilWorkflowNode


@dataclass(frozen=True)
class WorkflowNodeContext:
    run_id: str
    node: CouncilWorkflowNode
    workflow_input: dict[str, Any]
    incoming_outputs: dict[str, dict[str, Any]]
    outputs: dict[str, dict[str, Any]]
    node_statuses: dict[str, str]
    attempt: int
    iteration: int
    step: int
    phase_id: str | None
    loop_counts: dict[str, int]


NodeExecutor = Callable[[WorkflowNodeContext], dict[str, Any]]
CheckpointHook = Callable[[dict[str, Any]], None]


class WorkflowExecutionEngine:
    def __init__(
        self,
        execute_node: NodeExecutor,
        *,
        checkpoint_hook: CheckpointHook | None = None,
    ) -> None:
        self.execute_node = execute_node
        self.checkpoint_hook = checkpoint_hook

    def run(
        self,
        *,
        run_id: str,
        workflow: CouncilWorkflow,
        workflow_input: Mapping[str, Any],
        phase_id: str | None = None,
        failure_policy: str = "stop",
        resume_state: Mapping[str, Any] | None = None,
        resume_node_outputs: Mapping[str, Mapping[str, Any]] | None = None,
        max_duration_seconds: float | None = None,
        max_total_tokens: int | None = None,
        max_cost_usd: float | None = None,
    ) -> dict[str, Any]:
        state = _RuntimeState(
            engine=self,
            run_id=run_id,
            workflow=workflow,
            workflow_input=dict(workflow_input),
            phase_id=phase_id,
            failure_policy=failure_policy,
            resume_state=resume_state,
            resume_node_outputs=resume_node_outputs,
            max_duration_seconds=max_duration_seconds,
            max_total_tokens=max_total_tokens,
            max_cost_usd=max_cost_usd,
        )
        return state.execute()


class _RuntimeState:
    def __init__(
        self,
        *,
        engine: WorkflowExecutionEngine,
        run_id: str,
        workflow: CouncilWorkflow,
        workflow_input: dict[str, Any],
        phase_id: str | None,
        failure_policy: str,
        resume_state: Mapping[str, Any] | None,
        resume_node_outputs: Mapping[str, Mapping[str, Any]] | None,
        max_duration_seconds: float | None,
        max_total_tokens: int | None,
        max_cost_usd: float | None,
    ) -> None:
        if failure_policy not in {"stop", "continue", "replan"}:
            raise ValueError(f"Unsupported workflow failure policy: {failure_policy}")
        restored = resume_state or {}
        self.engine = engine
        self.run_id = run_id
        self.workflow = workflow
        self.workflow_input = workflow_input
        self.phase_id = phase_id
        self.failure_policy = failure_policy
        self.resume_node_outputs = {
            str(node_id): dict(output)
            for node_id, output in (resume_node_outputs or {}).items()
        }
        self.started_monotonic = time.monotonic()
        self.max_duration_seconds = max_duration_seconds if max_duration_seconds and max_duration_seconds > 0 else None
        self.max_total_tokens = max_total_tokens if max_total_tokens and max_total_tokens > 0 else None
        self.max_cost_usd = max_cost_usd if max_cost_usd is not None and max_cost_usd >= 0 else None
        self.nodes = {node.node_id: node for node in workflow.nodes}
        self.edges_by_target = {
            node_id: tuple(
                edge for edge in workflow.edges
                if not edge.loop and edge.target_node_id == node_id
            )
            for node_id in self.nodes
        }
        self.loop_edges_by_source = {
            node_id: tuple(
                edge for edge in workflow.edges
                if edge.loop and edge.source_node_id == node_id
            )
            for node_id in self.nodes
        }
        self.topological_order = self._topological_order()
        self.outputs = _dict_of_dicts(restored.get("outputs"))
        self.node_statuses = {
            str(node_id): str(status)
            for node_id, status in _mapping(restored.get("node_statuses")).items()
            if str(node_id) in self.nodes
        }
        self.attempt_counts = {
            str(node_id): max(0, _integer(value, 0))
            for node_id, value in _mapping(restored.get("attempt_counts")).items()
            if str(node_id) in self.nodes
        }
        self.node_iterations = {
            str(node_id): max(0, _integer(value, 0))
            for node_id, value in _mapping(restored.get("node_iterations")).items()
            if str(node_id) in self.nodes
        }
        self.loop_counts = {
            str(edge_id): max(0, _integer(value, 0))
            for edge_id, value in _mapping(restored.get("loop_counts")).items()
        }
        self.loop_signatures = {
            str(edge_id): str(signature)
            for edge_id, signature in _mapping(restored.get("loop_signatures")).items()
        }
        resource_usage = _mapping(restored.get("resource_usage"))
        self.total_tokens = max(0, _integer(resource_usage.get("total_tokens"), 0))
        self.estimated_cost_usd = max(0.0, _float(resource_usage.get("estimated_cost_usd"), 0.0))
        self.step_count = max(0, _integer(restored.get("step_count"), 0))
        self.events = [dict(event) for event in _dict_list(restored.get("events"))]
        self.terminal_status: str | None = None
        self.error: str | None = None
        self.limit_reached = False

    def execute(self) -> dict[str, Any]:
        input_node = next(node for node in self.workflow.nodes if node.node_type == "input")
        self.outputs[input_node.node_id] = dict(self.workflow_input)
        self.node_statuses[input_node.node_id] = "completed"
        self._event(input_node, "completed", attempt=0, detail={"source": "workflow_input"})
        for node_id in self.topological_order:
            if node_id == input_node.node_id:
                continue
            self._execute_node(node_id)
            if self.terminal_status in {"failed", "waiting", "replan_required"}:
                break
        status = self.terminal_status or self._final_status()
        checkpoint = self._checkpoint_payload(status)
        self._emit_checkpoint(checkpoint)
        return {
            "run_id": self.run_id,
            "status": status,
            "phase_id": self.phase_id,
            "outputs": self.outputs,
            "node_statuses": self.node_statuses,
            "attempt_counts": self.attempt_counts,
            "node_iterations": self.node_iterations,
            "loop_counts": self.loop_counts,
            "resource_usage": {
                "total_tokens": self.total_tokens,
                "estimated_cost_usd": round(self.estimated_cost_usd, 8),
            },
            "step_count": self.step_count,
            "limit_reached": self.limit_reached,
            "pending_node_ids": [
                node_id for node_id, node_status in self.node_statuses.items()
                if node_status == "waiting"
            ],
            "events": self.events,
            "error": self.error,
            "checkpoint": checkpoint,
        }

    def _execute_node(
        self,
        node_id: str,
        *,
        force: bool = False,
        suppressed_loop_edges: frozenset[str] = frozenset(),
    ) -> None:
        if self.terminal_status:
            return
        node = self.nodes[node_id]
        if self._duration_exceeded():
            self._fail(node, "Workflow max_duration_seconds exceeded")
            return
        if not force and self.node_statuses.get(node_id) == "completed":
            return
        if not node.participates_in(self.phase_id):
            self.node_statuses[node_id] = "skipped_phase"
            self._event(node, "skipped_phase", attempt=0)
            return
        active, active_edges = self._activation(node_id)
        if not active:
            self.node_statuses[node_id] = "skipped"
            self.outputs.pop(node_id, None)
            self._event(node, "skipped", attempt=0)
            return
        if node.node_type == "human_review":
            resumed_output = self.resume_node_outputs.get(node_id)
            if resumed_output is None:
                self.node_statuses[node_id] = "waiting"
                self.terminal_status = "waiting"
                self._event(node, "waiting", attempt=0)
                return
            self.outputs[node_id] = resumed_output
            self.node_statuses[node_id] = "completed"
            self._event(node, "completed", attempt=0, detail={"source": "human_input"})
            return
        incoming_outputs = {
            edge.source_node_id: self.outputs[edge.source_node_id]
            for edge in active_edges
            if edge.context_mode != "exclude" and edge.source_node_id in self.outputs
        }
        max_attempts = node.retry_policy.max_attempts
        iteration = self.node_iterations.get(node_id, -1) + 1
        self.node_iterations[node_id] = iteration
        start_attempt = self.attempt_counts.get(node_id, 0) + 1
        for attempt in range(start_attempt, start_attempt + max_attempts):
            if self.step_count >= self.workflow.max_steps:
                self._fail(node, "Workflow max_steps exceeded")
                return
            self.step_count += 1
            self.attempt_counts[node_id] = attempt
            self.node_statuses[node_id] = "running"
            self._event(node, "running", attempt=attempt)
            context = WorkflowNodeContext(
                run_id=self.run_id,
                node=node,
                workflow_input=dict(self.workflow_input),
                incoming_outputs={key: dict(value) for key, value in incoming_outputs.items()},
                outputs={key: dict(value) for key, value in self.outputs.items()},
                node_statuses=dict(self.node_statuses),
                attempt=attempt,
                iteration=iteration,
                step=self.step_count,
                phase_id=self.phase_id,
                loop_counts=dict(self.loop_counts),
            )
            try:
                raw_result = self.engine.execute_node(context)
                status, output = _normalize_node_result(raw_result)
                if status == "waiting":
                    self.node_statuses[node_id] = "waiting"
                    self.outputs[node_id] = output
                    self.terminal_status = "waiting"
                    self._event(node, "waiting", attempt=attempt)
                    return
                if status == "failed":
                    raise RuntimeError(str(output.get("error") or "Node returned failed status"))
                if status == "skipped":
                    self.node_statuses[node_id] = "skipped"
                    self.outputs.pop(node_id, None)
                    self._event(node, "skipped", attempt=attempt)
                    return
                self.outputs[node_id] = output
                self._consume_usage(output)
                self.node_statuses[node_id] = "completed"
                self._event(node, "completed", attempt=attempt)
                self._emit_checkpoint(self._checkpoint_payload("running"))
                self._process_loops(node_id, suppressed_loop_edges)
                return
            except Exception as exc:
                self._event(
                    node,
                    "attempt_failed",
                    attempt=attempt,
                    detail={"error": f"{type(exc).__name__}: {exc}"[:500]},
                )
                if attempt < start_attempt + max_attempts - 1:
                    if node.retry_policy.backoff_seconds:
                        time.sleep(node.retry_policy.backoff_seconds)
                    continue
                self._fail(node, f"{type(exc).__name__}: {exc}")
                return

    def _process_loops(self, node_id: str, suppressed: frozenset[str]) -> None:
        for edge in self.loop_edges_by_source[node_id]:
            if edge.edge_id in suppressed or self.terminal_status:
                continue
            maximum = min(edge.max_traversals, self.workflow.max_loop_iterations)
            while self._edge_passes(edge):
                traversals = self.loop_counts.get(edge.edge_id, 0)
                signature = _output_signature(self.outputs.get(node_id, {}))
                if traversals > 0 and self.loop_signatures.get(edge.edge_id) == signature:
                    self.limit_reached = True
                    self._event(
                        self.nodes[node_id],
                        "loop_stalled",
                        attempt=self.attempt_counts.get(node_id, 0),
                        detail={"edge_id": edge.edge_id, "traversal": traversals},
                    )
                    break
                self.loop_signatures[edge.edge_id] = signature
                if traversals >= maximum:
                    self.limit_reached = True
                    self._event(
                        self.nodes[node_id],
                        "loop_limit_reached",
                        attempt=self.attempt_counts.get(node_id, 0),
                        detail={"edge_id": edge.edge_id, "max_traversals": maximum},
                    )
                    break
                self.loop_counts[edge.edge_id] = traversals + 1
                region = self._loop_region(edge)
                self._event(
                    self.nodes[node_id],
                    "loop_started",
                    attempt=self.attempt_counts.get(node_id, 0),
                    detail={"edge_id": edge.edge_id, "traversal": traversals + 1, "region": region},
                )
                for region_node_id in region:
                    self.node_statuses.pop(region_node_id, None)
                    self.outputs.pop(region_node_id, None)
                next_suppressed = frozenset({*suppressed, edge.edge_id})
                for region_node_id in region:
                    self._execute_node(
                        region_node_id,
                        force=True,
                        suppressed_loop_edges=next_suppressed,
                    )
                    if self.terminal_status:
                        return

    def _activation(self, node_id: str) -> tuple[bool, tuple[CouncilWorkflowEdge, ...]]:
        incoming = self.edges_by_target[node_id]
        if not incoming:
            return False, ()
        groups: dict[str, list[CouncilWorkflowEdge]] = {}
        for edge in incoming:
            groups.setdefault(edge.activation_group or "__default__", []).append(edge)
        active_edges: list[CouncilWorkflowEdge] = []
        group_results: list[bool] = []
        for group_edges in groups.values():
            evaluations = [self._edge_passes(edge) for edge in group_edges]
            mode = group_edges[0].activation_mode
            passed = any(evaluations) if mode == "any" else all(evaluations)
            group_results.append(passed)
            if passed:
                active_edges.extend(
                    edge for edge, edge_passed in zip(group_edges, evaluations) if edge_passed
                )
        return any(group_results), tuple(active_edges)

    def _edge_passes(self, edge: CouncilWorkflowEdge) -> bool:
        if self.node_statuses.get(edge.source_node_id) != "completed":
            return False
        return evaluate_condition(
            edge.condition,
            {
                "input": self.workflow_input,
                "outputs": self.outputs,
                "current": self.outputs.get(edge.source_node_id, {}),
                "state": {
                    "step_count": self.step_count,
                    "loop_counts": self.loop_counts,
                    "node_statuses": self.node_statuses,
                },
            },
        )

    def _loop_region(self, edge: CouncilWorkflowEdge) -> list[str]:
        return [
            node_id for node_id in self.topological_order
            if self._path_exists(edge.target_node_id, node_id)
            and self._path_exists(node_id, edge.source_node_id)
        ]

    def _path_exists(self, source: str, target: str) -> bool:
        adjacency = {node_id: [] for node_id in self.nodes}
        for edge in self.workflow.edges:
            if not edge.loop:
                adjacency[edge.source_node_id].append(edge.target_node_id)
        pending = [source]
        visited: set[str] = set()
        while pending:
            current = pending.pop()
            if current == target:
                return True
            if current in visited:
                continue
            visited.add(current)
            pending.extend(adjacency[current])
        return False

    def _topological_order(self) -> list[str]:
        adjacency = {node_id: [] for node_id in self.nodes}
        indegrees = {node_id: 0 for node_id in self.nodes}
        for edge in self.workflow.edges:
            if edge.loop:
                continue
            adjacency[edge.source_node_id].append(edge.target_node_id)
            indegrees[edge.target_node_id] += 1
        queue = [node.node_id for node in self.workflow.nodes if indegrees[node.node_id] == 0]
        order: list[str] = []
        while queue:
            current = queue.pop(0)
            order.append(current)
            for target in adjacency[current]:
                indegrees[target] -= 1
                if indegrees[target] == 0:
                    queue.append(target)
        return order

    def _fail(self, node: CouncilWorkflowNode, error: str) -> None:
        self.node_statuses[node.node_id] = "failed"
        self.error = error[:500]
        self._event(node, "failed", attempt=self.attempt_counts.get(node.node_id, 0), detail={"error": self.error})
        if self.failure_policy == "stop":
            self.terminal_status = "failed"
        elif self.failure_policy == "replan":
            self.terminal_status = "replan_required"

    def _consume_usage(self, output: dict[str, Any]) -> None:
        usage = output.get("usage") if isinstance(output.get("usage"), dict) else {}
        self.total_tokens += max(
            0,
            _integer(
                usage.get("total_tokens"),
                _integer(usage.get("input_tokens"), 0) + _integer(usage.get("output_tokens"), 0),
            ),
        )
        self.estimated_cost_usd += max(0.0, _float(output.get("estimated_cost_usd"), 0.0))
        if self.max_total_tokens is not None and self.total_tokens > self.max_total_tokens:
            raise RuntimeError("Workflow max_total_tokens exceeded")
        if self.max_cost_usd is not None and self.estimated_cost_usd > self.max_cost_usd:
            raise RuntimeError("Workflow max_cost_usd exceeded")

    def _duration_exceeded(self) -> bool:
        return (
            self.max_duration_seconds is not None
            and time.monotonic() - self.started_monotonic > self.max_duration_seconds
        )

    def _final_status(self) -> str:
        statuses = set(self.node_statuses.values())
        if "failed" in statuses:
            return "partial" if self.failure_policy == "continue" else "failed"
        if self.limit_reached:
            return "completed_with_limits"
        return "completed"

    def _event(
        self,
        node: CouncilWorkflowNode,
        status: str,
        *,
        attempt: int,
        detail: dict[str, Any] | None = None,
    ) -> None:
        self.events.append(
            {
                "sequence": len(self.events) + 1,
                "run_id": self.run_id,
                "node_id": node.node_id,
                "node_type": node.node_type,
                "operation": node.operation,
                "status": status,
                "attempt": attempt,
                "step": self.step_count,
                "detail": detail or {},
                "created_at": datetime.now(timezone.utc).isoformat(),
            }
        )

    def _checkpoint_payload(self, status: str) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "status": status,
            "phase_id": self.phase_id,
            "outputs": self.outputs,
            "node_statuses": self.node_statuses,
            "attempt_counts": self.attempt_counts,
            "node_iterations": self.node_iterations,
            "loop_counts": self.loop_counts,
            "loop_signatures": self.loop_signatures,
            "resource_usage": {
                "total_tokens": self.total_tokens,
                "estimated_cost_usd": round(self.estimated_cost_usd, 8),
            },
            "step_count": self.step_count,
            "events": self.events,
        }

    def _emit_checkpoint(self, checkpoint: dict[str, Any]) -> None:
        if self.engine.checkpoint_hook:
            self.engine.checkpoint_hook(checkpoint)


def _normalize_node_result(result: Any) -> tuple[str, dict[str, Any]]:
    if not isinstance(result, dict):
        raise TypeError("Workflow node executor must return an object")
    if "status" in result and "output" in result:
        status = str(result.get("status") or "completed")
        output = result.get("output")
        if not isinstance(output, dict):
            raise TypeError("Workflow node output must be an object")
        if status not in {"completed", "waiting", "failed", "skipped"}:
            raise ValueError(f"Unsupported workflow node status: {status}")
        return status, dict(output)
    return "completed", dict(result)


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _dict_of_dicts(value: Any) -> dict[str, dict[str, Any]]:
    return {
        str(key): dict(item)
        for key, item in _mapping(value).items()
        if isinstance(item, Mapping)
    }


def _dict_list(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [dict(item) for item in value if isinstance(item, Mapping)]


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


def _output_signature(output: dict[str, Any]) -> str:
    encoded = json.dumps(output, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()
