from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from math import isfinite
from typing import Any


IDENTIFIER_PATTERN = re.compile(r"^[a-z][a-z0-9_-]{1,63}$")
SUPPORTED_PROTOCOLS = {"chat", "responses"}
SUPPORTED_SCHEDULING_MODES = {"parallel", "round_robin", "moderated"}
SUPPORTED_WORKFLOW_NODE_TYPES = {
    "input",
    "router",
    "deterministic",
    "agent",
    "gate",
    "subflow",
    "human_review",
    "aggregator",
    "chair",
}
SUPPORTED_CONTEXT_MODES = {"upstream", "selected", "latest", "claims_only", "summary", "none"}
SUPPORTED_EDGE_CONTEXT_MODES = {"inherit", "include", "exclude", "latest", "claims_only", "summary"}
SUPPORTED_CONDITION_OPERATORS = {
    "exists",
    "eq",
    "ne",
    "in",
    "not_in",
    "gt",
    "gte",
    "lt",
    "lte",
    "contains",
    "truthy",
    "falsy",
}
SUPPORTED_ACTIVATION_MODES = {"all", "any"}
REASONING_EFFORT_ORDER = ("provider_default", "none", "minimal", "low", "medium", "high", "xhigh", "max")
SUPPORTED_REASONING_EFFORTS = frozenset(REASONING_EFFORT_ORDER)
SUPPORTED_COST_TIERS = {"quick", "standard", "deep"}
SUPPORTED_FAILURE_POLICIES = {"stop", "continue", "replan"}
COUNCIL_WORKFLOW_VERSION = 1
COUNCIL_WORKFLOW_LATEST_VERSION = 2
SUPPORTED_COUNCIL_WORKFLOW_VERSIONS = {COUNCIL_WORKFLOW_VERSION, COUNCIL_WORKFLOW_LATEST_VERSION}
CONDITION_FIELD_PATTERN = re.compile(r"^[a-zA-Z][a-zA-Z0-9_.-]{0,255}$")


@dataclass(frozen=True)
class CouncilRole:
    role_id: str
    display_name: str
    stance: str
    objective: str
    enabled: bool = True
    order: int = 0
    site_id: str | None = None
    protocol: str | None = None
    model: str | None = None
    reasoning_effort: str = "provider_default"
    fallback_site_ids: tuple[str, ...] = ()
    system_prompt: str | None = None
    user_prompt_template: str | None = None

    @classmethod
    def from_payload(cls, payload: dict[str, Any], index: int = 0) -> "CouncilRole":
        role_id = _identifier(payload.get("role_id"), "role_id")
        protocol = _optional_string(payload.get("protocol"))
        if protocol and protocol not in SUPPORTED_PROTOCOLS:
            raise ValueError(f"Council role protocol is not supported: {protocol}")
        reasoning_effort = str(payload.get("reasoning_effort") or "provider_default").strip()
        if reasoning_effort not in SUPPORTED_REASONING_EFFORTS:
            raise ValueError(f"Council role reasoning_effort is not supported: {reasoning_effort}")
        return cls(
            role_id=role_id,
            display_name=str(payload.get("display_name") or role_id).strip(),
            stance=str(payload.get("stance") or "neutral").strip(),
            objective=str(payload.get("objective") or "独立审查输入并保留证据引用。").strip(),
            enabled=_boolean(payload.get("enabled"), True),
            order=_integer(payload.get("order"), index),
            site_id=_optional_string(payload.get("site_id")),
            protocol=protocol,
            model=_optional_string(payload.get("model")),
            reasoning_effort=reasoning_effort,
            fallback_site_ids=tuple(_string_list(payload.get("fallback_site_ids"))),
            system_prompt=_optional_string(payload.get("system_prompt")),
            user_prompt_template=_optional_string(payload.get("user_prompt_template")),
        )

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["fallback_site_ids"] = list(self.fallback_site_ids)
        return payload


@dataclass(frozen=True)
class CouncilPhase:
    phase_id: str
    label: str
    message_type: str
    scheduling_mode: str
    instructions: str
    participant_role_ids: tuple[str, ...] = ()
    moderator_role_id: str | None = None

    @classmethod
    def from_payload(cls, payload: dict[str, Any], index: int = 0) -> "CouncilPhase":
        phase_id = _identifier(payload.get("phase_id"), "phase_id")
        scheduling_mode = str(payload.get("scheduling_mode") or "round_robin").strip()
        if scheduling_mode not in SUPPORTED_SCHEDULING_MODES:
            raise ValueError(f"Council scheduling mode is not supported: {scheduling_mode}")
        return cls(
            phase_id=phase_id,
            label=str(payload.get("label") or phase_id).strip(),
            message_type=str(payload.get("message_type") or f"round-{index + 1}").strip(),
            scheduling_mode=scheduling_mode,
            instructions=str(payload.get("instructions") or "审查输入和前序消息，输出结构化观点。").strip(),
            participant_role_ids=tuple(_string_list(payload.get("participant_role_ids"))),
            moderator_role_id=_optional_string(payload.get("moderator_role_id")),
        )

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "phase_id": self.phase_id,
            "label": self.label,
            "message_type": self.message_type,
            "scheduling_mode": self.scheduling_mode,
            "instructions": self.instructions,
        }
        if self.participant_role_ids:
            payload["participant_role_ids"] = list(self.participant_role_ids)
        if self.moderator_role_id:
            payload["moderator_role_id"] = self.moderator_role_id
        return payload


@dataclass(frozen=True)
class CouncilCondition:
    field: str
    operator: str
    value: Any = None

    @classmethod
    def from_payload(cls, payload: Any) -> "CouncilCondition | None":
        if payload is None:
            return None
        if not isinstance(payload, dict):
            raise ValueError("Council workflow condition must be an object")
        field_name = str(payload.get("field") or "").strip()
        operator = str(payload.get("operator") or "").strip()
        if not CONDITION_FIELD_PATTERN.fullmatch(field_name):
            raise ValueError(f"Council workflow condition field is invalid: {field_name!r}")
        if operator not in SUPPORTED_CONDITION_OPERATORS:
            raise ValueError(f"Council workflow condition operator is not supported: {operator}")
        return cls(field=field_name, operator=operator, value=payload.get("value"))

    def to_dict(self) -> dict[str, Any]:
        payload = {"field": self.field, "operator": self.operator}
        if self.operator not in {"exists", "truthy", "falsy"} or self.value is not None:
            payload["value"] = self.value
        return payload


@dataclass(frozen=True)
class CouncilContextPolicy:
    mode: str = "upstream"
    source_node_ids: tuple[str, ...] = ()
    history_rounds: int = 3
    max_messages: int = 24
    content_fields: tuple[str, ...] = ()

    @classmethod
    def from_payload(cls, payload: Any) -> "CouncilContextPolicy":
        value = payload if isinstance(payload, dict) else {}
        mode = str(value.get("mode") or "upstream").strip()
        if mode not in SUPPORTED_CONTEXT_MODES:
            raise ValueError(f"Council context mode is not supported: {mode}")
        return cls(
            mode=mode,
            source_node_ids=tuple(_string_list(value.get("source_node_ids"))),
            history_rounds=max(0, min(8, _integer(value.get("history_rounds"), 3))),
            max_messages=max(0, min(64, _integer(value.get("max_messages"), 24))),
            content_fields=tuple(_string_list(value.get("content_fields"))),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "source_node_ids": list(self.source_node_ids),
            "history_rounds": self.history_rounds,
            "max_messages": self.max_messages,
            "content_fields": list(self.content_fields),
        }

    def is_default(self) -> bool:
        return self == CouncilContextPolicy()


@dataclass(frozen=True)
class CouncilRetryPolicy:
    max_attempts: int = 1
    backoff_seconds: float = 0.0

    @classmethod
    def from_payload(cls, payload: Any) -> "CouncilRetryPolicy":
        value = payload if isinstance(payload, dict) else {}
        return cls(
            max_attempts=max(1, min(5, _integer(value.get("max_attempts"), 1))),
            backoff_seconds=max(0.0, min(60.0, _finite_float(value.get("backoff_seconds"), 0.0))),
        )

    def to_dict(self) -> dict[str, Any]:
        return {"max_attempts": self.max_attempts, "backoff_seconds": self.backoff_seconds}

    def is_default(self) -> bool:
        return self == CouncilRetryPolicy()


@dataclass(frozen=True)
class CouncilChair:
    role_id: str = "chair_arbiter"
    display_name: str = "Chair Arbiter"
    site_id: str | None = None
    protocol: str | None = None
    model: str | None = None
    reasoning_effort: str = "provider_default"
    fallback_site_ids: tuple[str, ...] = ()
    system_prompt: str | None = None
    user_prompt_template: str | None = None

    @classmethod
    def from_payload(cls, payload: dict[str, Any] | None) -> "CouncilChair":
        value = payload or {}
        protocol = _optional_string(value.get("protocol"))
        if protocol and protocol not in SUPPORTED_PROTOCOLS:
            raise ValueError(f"Council chair protocol is not supported: {protocol}")
        reasoning_effort = str(value.get("reasoning_effort") or "provider_default").strip()
        if reasoning_effort not in SUPPORTED_REASONING_EFFORTS:
            raise ValueError(f"Council chair reasoning_effort is not supported: {reasoning_effort}")
        return cls(
            role_id=_identifier(value.get("role_id") or "chair_arbiter", "chair.role_id"),
            display_name=str(value.get("display_name") or "Chair Arbiter").strip(),
            site_id=_optional_string(value.get("site_id")),
            protocol=protocol,
            model=_optional_string(value.get("model")),
            reasoning_effort=reasoning_effort,
            fallback_site_ids=tuple(_string_list(value.get("fallback_site_ids"))),
            system_prompt=_optional_string(value.get("system_prompt")),
            user_prompt_template=_optional_string(value.get("user_prompt_template")),
        )

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["fallback_site_ids"] = list(self.fallback_site_ids)
        return payload


@dataclass(frozen=True)
class CouncilWorkflowNode:
    node_id: str
    node_type: str
    role_id: str | None
    position_x: float
    position_y: float
    operation: str | None = None
    phase_ids: tuple[str, ...] = ()
    config: dict[str, Any] = field(default_factory=dict)
    context_policy: CouncilContextPolicy = field(default_factory=CouncilContextPolicy)
    retry_policy: CouncilRetryPolicy = field(default_factory=CouncilRetryPolicy)

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "CouncilWorkflowNode":
        node_type = str(payload.get("node_type") or "").strip()
        if node_type not in SUPPORTED_WORKFLOW_NODE_TYPES:
            raise ValueError(f"Council workflow node_type is not supported: {node_type}")
        role_id = _optional_string(payload.get("role_id"))
        if node_type == "input" and role_id is not None:
            raise ValueError("Council workflow input node cannot reference a role")
        if node_type in {"agent", "chair"} and role_id is None:
            raise ValueError(f"Council workflow {node_type} node requires role_id")
        if node_type not in {"agent", "aggregator", "chair"} and role_id is not None:
            raise ValueError(f"Council workflow {node_type} node cannot reference a role")
        operation = _optional_identifier(payload.get("operation"), "workflow.operation")
        if node_type in {"router", "deterministic", "gate", "subflow", "human_review"} and not operation:
            raise ValueError(f"Council workflow {node_type} node requires operation")
        if node_type == "aggregator" and role_id is None and not operation:
            raise ValueError("Council workflow deterministic aggregator requires operation")
        position = payload.get("position") if isinstance(payload.get("position"), dict) else {}
        return cls(
            node_id=_identifier(payload.get("node_id"), "workflow.node_id"),
            node_type=node_type,
            role_id=role_id,
            position_x=_finite_float(position.get("x"), 0.0),
            position_y=_finite_float(position.get("y"), 0.0),
            operation=operation,
            phase_ids=tuple(_string_list(payload.get("phase_ids"))),
            config=dict(payload.get("config")) if isinstance(payload.get("config"), dict) else {},
            context_policy=CouncilContextPolicy.from_payload(payload.get("context_policy")),
            retry_policy=CouncilRetryPolicy.from_payload(payload.get("retry_policy")),
        )

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "node_id": self.node_id,
            "node_type": self.node_type,
            "role_id": self.role_id,
            "position": {"x": self.position_x, "y": self.position_y},
        }
        if self.operation:
            payload["operation"] = self.operation
        if self.phase_ids:
            payload["phase_ids"] = list(self.phase_ids)
        if self.config:
            payload["config"] = self.config
        if not self.context_policy.is_default():
            payload["context_policy"] = self.context_policy.to_dict()
        if not self.retry_policy.is_default():
            payload["retry_policy"] = self.retry_policy.to_dict()
        return payload

    def participates_in(self, phase_id: str | None) -> bool:
        return not self.phase_ids or phase_id is None or phase_id in self.phase_ids


@dataclass(frozen=True)
class CouncilWorkflowEdge:
    edge_id: str
    source_node_id: str
    target_node_id: str
    condition: CouncilCondition | None = None
    activation_group: str | None = None
    activation_mode: str = "all"
    context_mode: str = "inherit"
    loop: bool = False
    max_traversals: int = 1

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "CouncilWorkflowEdge":
        activation_mode = str(payload.get("activation_mode") or "all").strip()
        if activation_mode not in SUPPORTED_ACTIVATION_MODES:
            raise ValueError(f"Council workflow activation mode is not supported: {activation_mode}")
        context_mode = str(payload.get("context_mode") or "inherit").strip()
        if context_mode not in SUPPORTED_EDGE_CONTEXT_MODES:
            raise ValueError(f"Council workflow edge context mode is not supported: {context_mode}")
        return cls(
            edge_id=_identifier(payload.get("edge_id"), "workflow.edge_id"),
            source_node_id=_identifier(payload.get("source_node_id"), "workflow.source_node_id"),
            target_node_id=_identifier(payload.get("target_node_id"), "workflow.target_node_id"),
            condition=CouncilCondition.from_payload(payload.get("condition")),
            activation_group=_optional_identifier(payload.get("activation_group"), "workflow.activation_group"),
            activation_mode=activation_mode,
            context_mode=context_mode,
            loop=_boolean(payload.get("loop"), False),
            max_traversals=max(1, min(8, _integer(payload.get("max_traversals"), 1))),
        )

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "edge_id": self.edge_id,
            "source_node_id": self.source_node_id,
            "target_node_id": self.target_node_id,
        }
        if self.condition:
            payload["condition"] = self.condition.to_dict()
        if self.activation_group:
            payload["activation_group"] = self.activation_group
        if self.activation_mode != "all":
            payload["activation_mode"] = self.activation_mode
        if self.context_mode != "inherit":
            payload["context_mode"] = self.context_mode
        if self.loop:
            payload["loop"] = True
            payload["max_traversals"] = self.max_traversals
        return payload


@dataclass(frozen=True)
class CouncilWorkflow:
    version: int
    nodes: tuple[CouncilWorkflowNode, ...]
    edges: tuple[CouncilWorkflowEdge, ...]
    max_steps: int = 100
    max_loop_iterations: int = 3

    @classmethod
    def from_payload(
        cls,
        payload: dict[str, Any] | None,
        *,
        roles: tuple[CouncilRole, ...],
        chair: CouncilChair,
    ) -> "CouncilWorkflow":
        if not isinstance(payload, dict):
            return cls.default_for(roles=roles, chair=chair)
        version = _integer(payload.get("version"), COUNCIL_WORKFLOW_VERSION)
        if version not in SUPPORTED_COUNCIL_WORKFLOW_VERSIONS:
            raise ValueError(f"Council workflow version is not supported: {version}")
        workflow = cls(
            version=version,
            nodes=tuple(CouncilWorkflowNode.from_payload(item) for item in _dict_list(payload.get("nodes"))),
            edges=tuple(CouncilWorkflowEdge.from_payload(item) for item in _dict_list(payload.get("edges"))),
            max_steps=max(1, min(500, _integer(payload.get("max_steps"), 100))),
            max_loop_iterations=max(1, min(8, _integer(payload.get("max_loop_iterations"), 3))),
        )
        workflow._validate(roles=roles, chair=chair, phases=())
        return workflow

    @classmethod
    def default_for(
        cls,
        *,
        roles: tuple[CouncilRole, ...],
        chair: CouncilChair,
    ) -> "CouncilWorkflow":
        input_node = CouncilWorkflowNode("input_context", "input", None, 40.0, 220.0)
        chair_node = CouncilWorkflowNode(
            f"node_{chair.role_id}",
            "chair",
            chair.role_id,
            920.0,
            220.0,
        )
        risk_roles = [role for role in roles if role.stance == "risk"]
        aggregator = risk_roles[0] if len(risk_roles) == 1 and len(roles) > 2 else None
        upstream_roles = [role for role in roles if role != aggregator]
        nodes = [input_node]
        for index, role in enumerate(upstream_roles):
            nodes.append(
                CouncilWorkflowNode(
                    f"node_{role.role_id}",
                    "agent",
                    role.role_id,
                    320.0,
                    60.0 + index * 170.0,
                )
            )
        if aggregator is not None:
            nodes.append(
                CouncilWorkflowNode(
                    f"node_{aggregator.role_id}",
                    "agent",
                    aggregator.role_id,
                    640.0,
                    220.0,
                )
            )
        nodes.append(chair_node)

        edge_pairs: list[tuple[str, str]] = []
        if aggregator is None:
            for role in roles:
                role_node_id = f"node_{role.role_id}"
                edge_pairs.extend(
                    [
                        (input_node.node_id, role_node_id),
                        (role_node_id, chair_node.node_id),
                    ]
                )
        else:
            aggregator_node_id = f"node_{aggregator.role_id}"
            for role in upstream_roles:
                role_node_id = f"node_{role.role_id}"
                edge_pairs.extend(
                    [
                        (input_node.node_id, role_node_id),
                        (role_node_id, aggregator_node_id),
                    ]
                )
            edge_pairs.append((aggregator_node_id, chair_node.node_id))
        workflow = cls(
            version=COUNCIL_WORKFLOW_VERSION,
            nodes=tuple(nodes),
            edges=tuple(
                CouncilWorkflowEdge(f"edge_{index}", source, target)
                for index, (source, target) in enumerate(edge_pairs, start=1)
            ),
        )
        workflow._validate(roles=roles, chair=chair, phases=())
        return workflow

    def execution_layers(
        self,
        roles: tuple[CouncilRole, ...],
        phase_id: str | None = None,
        participant_role_ids: tuple[str, ...] = (),
    ) -> tuple[tuple[CouncilRole, ...], ...]:
        participant_set = set(participant_role_ids)
        active_roles = tuple(
            role
            for role in roles
            if role.enabled
            and (not participant_set or role.role_id in participant_set)
            and self.node_for_role(role.role_id).participates_in(phase_id)
        )
        dependencies = {
            role.role_id: {
                candidate.role_id
                for candidate in active_roles
                if candidate.role_id != role.role_id
                and self._role_reaches(candidate.role_id, role.role_id, include_loop=False)
            }
            for role in active_roles
        }
        layers: list[tuple[CouncilRole, ...]] = []
        remaining = {role.role_id for role in active_roles}
        while remaining:
            ready_ids = {
                role_id
                for role_id in remaining
                if not dependencies[role_id].intersection(remaining)
            }
            if not ready_ids:
                raise ValueError("Council workflow active role dependencies contain a cycle")
            layers.append(tuple(role for role in active_roles if role.role_id in ready_ids))
            remaining.difference_update(ready_ids)
        return tuple(layers)

    def upstream_role_ids(
        self,
        role_id: str,
        roles: tuple[CouncilRole, ...],
        phase_id: str | None = None,
        participant_role_ids: tuple[str, ...] = (),
    ) -> tuple[str, ...]:
        participant_set = set(participant_role_ids)
        return tuple(
            role.role_id
            for role in roles
            if role.enabled
            and role.role_id != role_id
            and (not participant_set or role.role_id in participant_set)
            and self.node_for_role(role.role_id).participates_in(phase_id)
            and self._role_reaches(role.role_id, role_id, include_loop=False)
        )

    def context_role_ids(
        self,
        role_id: str,
        roles: tuple[CouncilRole, ...],
        phase_id: str | None = None,
        participant_role_ids: tuple[str, ...] = (),
    ) -> tuple[str, ...]:
        node = self.node_for_role(role_id)
        if node.context_policy.mode == "none":
            return ()
        participant_set = set(participant_role_ids)
        role_by_node_id = {
            candidate.node_id: str(candidate.role_id)
            for candidate in self.nodes
            if candidate.role_id
        }
        if node.context_policy.mode == "selected":
            selected = {
                role_by_node_id[node_id]
                for node_id in node.context_policy.source_node_ids
                if node_id in role_by_node_id
            }
            return tuple(role.role_id for role in roles if role.role_id in selected and role.enabled)
        return tuple(
            role.role_id
            for role in roles
            if role.enabled
            and role.role_id != role_id
            and (not participant_set or role.role_id in participant_set)
            and self.node_for_role(role.role_id).participates_in(phase_id)
            and self._role_reaches(role.role_id, role_id, include_loop=False, context_only=True)
        )

    def chair_input_role_ids(self, roles: tuple[CouncilRole, ...]) -> tuple[str, ...]:
        active_roles = tuple(role for role in roles if role.enabled)
        return tuple(
            role.role_id
            for role in active_roles
            if not any(
                other.role_id != role.role_id and self._role_reaches(role.role_id, other.role_id, include_loop=False)
                for other in active_roles
            )
        )

    def node_for_role(self, role_id: str) -> CouncilWorkflowNode:
        return next(node for node in self.nodes if node.role_id == role_id)

    def node_id_for_role(self, role_id: str) -> str:
        return self.node_for_role(role_id).node_id

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "version": self.version,
            "nodes": [node.to_dict() for node in self.nodes],
            "edges": [edge.to_dict() for edge in self.edges],
        }
        if self.version >= 2:
            payload["max_steps"] = self.max_steps
            payload["max_loop_iterations"] = self.max_loop_iterations
        return payload

    def validate_for_template(
        self,
        *,
        roles: tuple[CouncilRole, ...],
        chair: CouncilChair,
        phases: tuple[CouncilPhase, ...],
    ) -> None:
        self._validate(roles=roles, chair=chair, phases=phases)

    def _validate(
        self,
        *,
        roles: tuple[CouncilRole, ...],
        chair: CouncilChair,
        phases: tuple[CouncilPhase, ...],
    ) -> None:
        node_ids = [node.node_id for node in self.nodes]
        edge_ids = [edge.edge_id for edge in self.edges]
        if len(node_ids) != len(set(node_ids)):
            raise ValueError("Council workflow node_id must be unique")
        if len(edge_ids) != len(set(edge_ids)):
            raise ValueError("Council workflow edge_id must be unique")
        if not self.nodes:
            raise ValueError("Council workflow requires nodes")

        input_nodes = [node for node in self.nodes if node.node_type == "input"]
        chair_nodes = [node for node in self.nodes if node.node_type == "chair"]
        role_nodes = [
            node
            for node in self.nodes
            if node.node_type in {"agent", "aggregator"} and node.role_id
        ]
        if len(input_nodes) != 1:
            raise ValueError("Council workflow requires exactly one input node")
        if len(chair_nodes) != 1:
            raise ValueError("Council workflow requires exactly one chair node")
        if chair_nodes[0].role_id != chair.role_id:
            raise ValueError("Council workflow chair node must reference chair.role_id")
        expected_role_ids = {role.role_id for role in roles}
        agent_role_ids = [str(node.role_id) for node in role_nodes]
        if len(agent_role_ids) != len(set(agent_role_ids)):
            raise ValueError("Council workflow role_id must be mapped once")
        if set(agent_role_ids) != expected_role_ids:
            raise ValueError("Council workflow agent nodes must map every Council role exactly once")
        phase_ids = {phase.phase_id for phase in phases}
        for node in self.nodes:
            if self.version == 1 and (
                node.node_type not in {"input", "agent", "chair"}
                or node.operation
                or node.phase_ids
                or node.config
                or not node.context_policy.is_default()
                or not node.retry_policy.is_default()
            ):
                raise ValueError(f"Council workflow node requires version 2: {node.node_id}")
            if phases and any(phase_id not in phase_ids for phase_id in node.phase_ids):
                raise ValueError(f"Council workflow node references unknown phase: {node.node_id}")
            if node.context_policy.source_node_ids:
                unknown_sources = set(node.context_policy.source_node_ids).difference(node_ids)
                if unknown_sources:
                    raise ValueError(f"Council workflow context references unknown node: {sorted(unknown_sources)[0]}")

        nodes_by_id = {node.node_id: node for node in self.nodes}
        edge_pairs: set[tuple[str, str]] = set()
        for edge in self.edges:
            if edge.source_node_id not in nodes_by_id or edge.target_node_id not in nodes_by_id:
                raise ValueError(f"Council workflow edge references unknown node: {edge.edge_id}")
            if edge.source_node_id == edge.target_node_id:
                raise ValueError(f"Council workflow self edge is not allowed: {edge.edge_id}")
            pair = (edge.source_node_id, edge.target_node_id)
            if pair in edge_pairs:
                raise ValueError("Council workflow duplicate source/target edge is not allowed")
            edge_pairs.add(pair)
            if nodes_by_id[edge.target_node_id].node_type == "input":
                raise ValueError("Council workflow cannot connect into input node")
            if nodes_by_id[edge.source_node_id].node_type == "chair":
                raise ValueError("Council workflow cannot connect out of chair node")
            if edge.loop:
                if self.version < 2:
                    raise ValueError("Council workflow loop edges require version 2")
                if edge.condition is None:
                    raise ValueError(f"Council workflow loop edge requires condition: {edge.edge_id}")
            if self.version == 1 and (
                edge.condition
                or edge.activation_group
                or edge.activation_mode != "all"
                or edge.context_mode != "inherit"
            ):
                raise ValueError(f"Council workflow edge requires version 2: {edge.edge_id}")

        activation_modes: dict[tuple[str, str], str] = {}
        for edge in self.edges:
            if not edge.activation_group:
                continue
            key = (edge.target_node_id, edge.activation_group)
            previous_mode = activation_modes.setdefault(key, edge.activation_mode)
            if previous_mode != edge.activation_mode:
                raise ValueError(
                    f"Council workflow activation group mixes modes: {edge.activation_group}"
                )

        adjacency = self._adjacency(include_loop=False)
        indegrees = {node_id: 0 for node_id in node_ids}
        for targets in adjacency.values():
            for target in targets:
                indegrees[target] += 1
        queue = [node_id for node_id, degree in indegrees.items() if degree == 0]
        visited_count = 0
        while queue:
            current = queue.pop(0)
            visited_count += 1
            for target in adjacency[current]:
                indegrees[target] -= 1
                if indegrees[target] == 0:
                    queue.append(target)
        if visited_count != len(self.nodes):
            if self.version == 1:
                raise ValueError("Council workflow must be a directed acyclic graph")
            raise ValueError("Council workflow non-loop edges must be a directed acyclic graph")

        for edge in self.edges:
            if edge.loop and not self._path_exists(
                edge.target_node_id,
                edge.source_node_id,
                include_loop=False,
            ):
                raise ValueError(f"Council workflow loop edge must close an existing forward path: {edge.edge_id}")

        input_id = input_nodes[0].node_id
        chair_id = chair_nodes[0].node_id
        for node in [item for item in self.nodes if item.node_type not in {"input", "chair"}]:
            if not self._path_exists(input_id, node.node_id, include_loop=False):
                raise ValueError(f"Council workflow node is not reachable from input: {node.node_id}")
            if not self._path_exists(node.node_id, chair_id, include_loop=False):
                raise ValueError(f"Council workflow node cannot reach chair: {node.node_id}")

    def _role_reaches(
        self,
        source_role_id: str,
        target_role_id: str,
        *,
        include_loop: bool,
        context_only: bool = False,
    ) -> bool:
        return self._path_exists(
            self.node_id_for_role(source_role_id),
            self.node_id_for_role(target_role_id),
            include_loop=include_loop,
            context_only=context_only,
        )

    def _path_exists(
        self,
        source_node_id: str,
        target_node_id: str,
        *,
        include_loop: bool = True,
        context_only: bool = False,
    ) -> bool:
        adjacency = self._adjacency(include_loop=include_loop, context_only=context_only)
        pending = [source_node_id]
        visited: set[str] = set()
        while pending:
            current = pending.pop()
            if current == target_node_id:
                return True
            if current in visited:
                continue
            visited.add(current)
            pending.extend(adjacency.get(current, ()))
        return False

    def _adjacency(
        self,
        *,
        include_loop: bool = True,
        context_only: bool = False,
    ) -> dict[str, tuple[str, ...]]:
        adjacency: dict[str, list[str]] = {node.node_id: [] for node in self.nodes}
        for edge in self.edges:
            if not include_loop and edge.loop:
                continue
            if context_only and edge.context_mode == "exclude":
                continue
            adjacency[edge.source_node_id].append(edge.target_node_id)
        return {node_id: tuple(targets) for node_id, targets in adjacency.items()}


@dataclass(frozen=True)
class CouncilRoundPolicy:
    default_rounds: int = 3
    min_rounds: int = 1
    max_rounds: int = 8
    stop_condition: CouncilCondition | None = None

    @classmethod
    def from_payload(cls, payload: Any) -> "CouncilRoundPolicy":
        value = payload if isinstance(payload, dict) else {}
        minimum = max(1, min(32, _integer(value.get("min_rounds"), 1)))
        maximum = max(minimum, min(32, _integer(value.get("max_rounds"), 8)))
        default = max(minimum, min(maximum, _integer(value.get("default_rounds"), 3)))
        return cls(
            default_rounds=default,
            min_rounds=minimum,
            max_rounds=maximum,
            stop_condition=CouncilCondition.from_payload(value.get("stop_condition")),
        )

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "default_rounds": self.default_rounds,
            "min_rounds": self.min_rounds,
            "max_rounds": self.max_rounds,
        }
        if self.stop_condition:
            payload["stop_condition"] = self.stop_condition.to_dict()
        return payload


@dataclass(frozen=True)
class CouncilTemplate:
    template_id: str
    display_name: str
    enabled: bool
    roles: tuple[CouncilRole, ...]
    phases: tuple[CouncilPhase, ...]
    chair: CouncilChair
    workflow: CouncilWorkflow
    quorum_ratio: float = 0.5
    max_roles: int = 12
    description: str = ""
    cost_tier: str = "standard"
    failure_policy: str = "stop"
    round_policy: CouncilRoundPolicy = field(default_factory=CouncilRoundPolicy)
    builtin: bool = False
    template_kind: str = "custom"
    recommended_for: tuple[str, ...] = ()

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "CouncilTemplate":
        template_id = _identifier(payload.get("template_id"), "template_id")
        roles = tuple(
            sorted(
                (CouncilRole.from_payload(item, index) for index, item in enumerate(_dict_list(payload.get("roles")))),
                key=lambda item: (item.order, item.role_id),
            )
        )
        phases = tuple(
            CouncilPhase.from_payload(item, index)
            for index, item in enumerate(_dict_list(payload.get("phases")))
        )
        max_roles = max(2, min(12, _integer(payload.get("max_roles"), 12)))
        enabled_roles = [role for role in roles if role.enabled]
        role_ids = [role.role_id for role in roles]
        phase_ids = [phase.phase_id for phase in phases]
        if len(role_ids) != len(set(role_ids)):
            raise ValueError(f"Council template {template_id} role_id must be unique")
        if len(phase_ids) != len(set(phase_ids)):
            raise ValueError(f"Council template {template_id} phase_id must be unique")
        if not 2 <= len(roles) <= max_roles:
            raise ValueError(
                f"Council template {template_id} requires 2-{max_roles} roles; got {len(roles)}"
            )
        if not 2 <= len(enabled_roles) <= max_roles:
            raise ValueError(
                f"Council template {template_id} requires 2-{max_roles} enabled roles; got {len(enabled_roles)}"
            )
        if not phases:
            raise ValueError(f"Council template {template_id} requires at least one phase")
        chair = CouncilChair.from_payload(payload.get("chair") if isinstance(payload.get("chair"), dict) else None)
        workflow = CouncilWorkflow.from_payload(
            payload.get("workflow") if isinstance(payload.get("workflow"), dict) else None,
            roles=roles,
            chair=chair,
        )
        workflow.validate_for_template(roles=roles, chair=chair, phases=phases)
        phase_role_ids = {role_id for phase in phases for role_id in phase.participant_role_ids}
        unknown_phase_roles = phase_role_ids.difference(role_ids)
        if unknown_phase_roles:
            raise ValueError(f"Council phase references unknown role: {sorted(unknown_phase_roles)[0]}")
        for phase in phases:
            if phase.moderator_role_id and phase.moderator_role_id not in role_ids:
                raise ValueError(f"Council phase moderator is unknown: {phase.moderator_role_id}")
            if phase.scheduling_mode == "moderated" and not phase.moderator_role_id:
                raise ValueError(f"Council moderated phase requires moderator_role_id: {phase.phase_id}")
            if phase.moderator_role_id:
                phase_roles = tuple(
                    role for role in roles
                    if role.enabled and (not phase.participant_role_ids or role.role_id in phase.participant_role_ids)
                )
                if any(
                    role.role_id != phase.moderator_role_id
                    and workflow._role_reaches(phase.moderator_role_id, role.role_id, include_loop=False)
                    for role in phase_roles
                ):
                    raise ValueError(
                        f"Council phase moderator must be terminal among phase participants: {phase.phase_id}"
                    )
        cost_tier = str(payload.get("cost_tier") or "standard").strip()
        if cost_tier not in SUPPORTED_COST_TIERS:
            raise ValueError(f"Council cost_tier is not supported: {cost_tier}")
        failure_policy = str(payload.get("failure_policy") or "stop").strip()
        if failure_policy not in SUPPORTED_FAILURE_POLICIES:
            raise ValueError(f"Council failure_policy is not supported: {failure_policy}")
        return cls(
            template_id=template_id,
            display_name=str(payload.get("display_name") or template_id).strip(),
            enabled=_boolean(payload.get("enabled"), True),
            roles=roles,
            phases=phases,
            chair=chair,
            workflow=workflow,
            quorum_ratio=max(0.25, min(1.0, _float(payload.get("quorum_ratio"), 0.5))),
            max_roles=max_roles,
            description=str(payload.get("description") or "").strip(),
            cost_tier=cost_tier,
            failure_policy=failure_policy,
            round_policy=CouncilRoundPolicy.from_payload(payload.get("round_policy")),
            builtin=_boolean(payload.get("builtin"), False),
            template_kind=str(payload.get("template_kind") or "custom").strip(),
            recommended_for=tuple(_string_list(payload.get("recommended_for"))),
        )

    def enabled_roles(self) -> tuple[CouncilRole, ...]:
        return tuple(role for role in self.roles if role.enabled)

    def phase_for_round(self, round_index: int) -> CouncilPhase:
        return self.phases[min(max(1, round_index), len(self.phases)) - 1]

    def roles_for_phase(self, phase: CouncilPhase) -> tuple[CouncilRole, ...]:
        participant_set = set(phase.participant_role_ids)
        return tuple(
            role
            for role in self.roles
            if role.enabled
            and (not participant_set or role.role_id in participant_set)
            and self.workflow.node_for_role(role.role_id).participates_in(phase.phase_id)
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "template_id": self.template_id,
            "display_name": self.display_name,
            "enabled": self.enabled,
            "roles": [role.to_dict() for role in self.roles],
            "phases": [phase.to_dict() for phase in self.phases],
            "chair": self.chair.to_dict(),
            "workflow": self.workflow.to_dict(),
            "quorum_ratio": self.quorum_ratio,
            "max_roles": self.max_roles,
            "description": self.description,
            "cost_tier": self.cost_tier,
            "failure_policy": self.failure_policy,
            "round_policy": self.round_policy.to_dict(),
            "builtin": self.builtin,
            "template_kind": self.template_kind,
            "recommended_for": list(self.recommended_for),
        }


def _identifier(value: Any, field_name: str) -> str:
    clean = str(value or "").strip()
    if not IDENTIFIER_PATTERN.fullmatch(clean):
        raise ValueError(f"Invalid Council {field_name}: {clean!r}")
    return clean


def _optional_identifier(value: Any, field_name: str) -> str | None:
    clean = _optional_string(value)
    return _identifier(clean, field_name) if clean else None


def _optional_string(value: Any) -> str | None:
    if value is None:
        return None
    clean = str(value).strip()
    return clean or None


def _string_list(value: Any) -> list[str]:
    if isinstance(value, str):
        items = value.split(",")
    elif isinstance(value, (list, tuple)):
        items = value
    else:
        items = []
    return list(dict.fromkeys(str(item).strip() for item in items if str(item).strip()))


def _dict_list(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _boolean(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on", "是", "开启"}
    return bool(value)


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


def _finite_float(value: Any, default: float) -> float:
    parsed = _float(value, default)
    return parsed if isfinite(parsed) else default
