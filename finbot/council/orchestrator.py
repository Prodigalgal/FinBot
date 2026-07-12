from __future__ import annotations

import math
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any

from finbot.council.conditions import evaluate_condition
from finbot.council.models import CouncilPhase, CouncilRole, CouncilTemplate


@dataclass(frozen=True)
class CouncilTurnRequest:
    council_id: str
    round_index: int
    turn_index: int
    phase: CouncilPhase
    role: CouncilRole
    shared_context: dict[str, Any]
    prior_messages: tuple[dict[str, Any], ...]
    reply_to_message_ids: tuple[str, ...]
    workflow_node_id: str
    upstream_role_ids: tuple[str, ...]


TurnExecutor = Callable[[CouncilTurnRequest], dict[str, Any]]


class CouncilOrchestrator:
    def __init__(self, execute_turn: TurnExecutor, max_parallel_roles: int = 8):
        self.execute_turn = execute_turn
        self.max_parallel_roles = max(1, max_parallel_roles)

    def run(
        self,
        *,
        council_id: str,
        template: CouncilTemplate,
        shared_context: dict[str, Any],
        rounds: int,
    ) -> dict[str, Any]:
        roles = template.enabled_roles()
        round_count = max(
            template.round_policy.min_rounds,
            min(rounds, template.round_policy.max_rounds),
        )
        messages: list[dict[str, Any]] = []
        round_summaries: list[dict[str, Any]] = []
        partial = False
        stop_reason: str | None = None
        max_layer_count = 0
        for round_index in range(1, round_count + 1):
            phase = template.phase_for_round(round_index)
            phase_roles = template.roles_for_phase(phase)
            execution_layers = template.workflow.execution_layers(
                template.roles,
                phase_id=phase.phase_id,
                participant_role_ids=phase.participant_role_ids,
            )
            round_messages, layer_summaries = self._run_phase(
                council_id=council_id,
                round_index=round_index,
                phase=phase,
                template=template,
                execution_layers=execution_layers,
                shared_context=shared_context,
                previous_messages=messages,
            )
            messages.extend(round_messages)
            max_layer_count = max(max_layer_count, len(layer_summaries))
            completed = sum(message.get("status") == "completed" for message in round_messages)
            required = max(1, math.ceil(len(phase_roles) * template.quorum_ratio))
            round_summary = {
                    "round_index": round_index,
                    "phase_id": phase.phase_id,
                    "scheduling_mode": phase.scheduling_mode,
                    "message_count": len(round_messages),
                    "completed_message_count": completed,
                    "required_quorum": required,
                    "quorum_met": completed >= required,
                    "workflow_layer_count": len(layer_summaries),
                    "workflow_layers": layer_summaries,
                    "participant_role_ids": [role.role_id for role in phase_roles],
                }
            round_summaries.append(round_summary)
            if completed < required:
                partial = True
                if completed == 0:
                    break
            if (
                template.round_policy.stop_condition
                and round_index >= template.round_policy.min_rounds
                and evaluate_condition(
                    template.round_policy.stop_condition,
                    {
                        "round": round_summary,
                        "state": {
                            "rounds_completed": len(round_summaries),
                            "completed_message_count": sum(
                                message.get("status") == "completed" for message in messages
                            ),
                        },
                    },
                )
            ):
                stop_reason = "stop_condition_met"
                break

        completed_total = sum(message.get("status") == "completed" for message in messages)
        if not completed_total:
            status = "failed"
        elif partial:
            status = "partial_ready_for_synthesis"
        else:
            status = "ready_for_synthesis"
        return {
            "status": status,
            "template_id": template.template_id,
            "rounds_requested": round_count,
            "rounds_completed": len(round_summaries),
            "stop_reason": stop_reason,
            "round_summaries": round_summaries,
            "messages": messages,
            "summary": {
                "role_count": len(roles),
                "message_count": len(messages),
                "completed_message_count": completed_total,
                "failed_message_count": len(messages) - completed_total,
                "roles": [role.role_id for role in roles],
                "phases": [summary["phase_id"] for summary in round_summaries],
                "workflow_node_count": len(template.workflow.nodes),
                "workflow_edge_count": len(template.workflow.edges),
                "workflow_layer_count": max_layer_count,
            },
        }

    def _run_phase(
        self,
        *,
        council_id: str,
        round_index: int,
        phase: CouncilPhase,
        template: CouncilTemplate,
        execution_layers: tuple[tuple[CouncilRole, ...], ...],
        shared_context: dict[str, Any],
        previous_messages: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        round_messages: list[dict[str, Any]] = []
        layer_summaries: list[dict[str, Any]] = []
        phase_roles = template.roles_for_phase(phase)
        turn_indices = {
            role.role_id: index
            for index, role in enumerate(phase_roles, start=1)
        }
        moderator_role_id = phase.moderator_role_id if phase.scheduling_mode == "moderated" else None
        for layer_index, layer_roles in enumerate(execution_layers, start=1):
            active_layer_roles = tuple(role for role in layer_roles if role.role_id != moderator_role_id)
            if not active_layer_roles:
                continue
            if phase.scheduling_mode in {"parallel", "moderated"}:
                layer_messages = self._run_parallel_layer(
                    council_id=council_id,
                    round_index=round_index,
                    phase=phase,
                    roles=active_layer_roles,
                    template=template,
                    shared_context=shared_context,
                    historical_messages=previous_messages,
                    current_round_messages=round_messages,
                    turn_indices=turn_indices,
                )
            else:
                layer_messages = self._run_round_robin_layer(
                    council_id=council_id,
                    round_index=round_index,
                    phase=phase,
                    roles=active_layer_roles,
                    template=template,
                    shared_context=shared_context,
                    historical_messages=previous_messages,
                    current_round_messages=round_messages,
                    turn_indices=turn_indices,
                )
            round_messages.extend(layer_messages)
            layer_summaries.append(
                {
                    "layer_index": layer_index,
                    "role_ids": [role.role_id for role in active_layer_roles],
                    "message_count": len(layer_messages),
                    "completed_message_count": sum(
                        message.get("status") == "completed" for message in layer_messages
                    ),
                }
            )
        if moderator_role_id:
            moderator = next(role for role in phase_roles if role.role_id == moderator_role_id)
            moderator_messages = self._run_round_robin_layer(
                council_id=council_id,
                round_index=round_index,
                phase=phase,
                roles=(moderator,),
                template=template,
                shared_context=shared_context,
                historical_messages=previous_messages,
                current_round_messages=round_messages,
                turn_indices=turn_indices,
                include_all_current=True,
            )
            round_messages.extend(moderator_messages)
            layer_summaries.append(
                {
                    "layer_index": len(layer_summaries) + 1,
                    "role_ids": [moderator_role_id],
                    "message_count": len(moderator_messages),
                    "completed_message_count": sum(
                        message.get("status") == "completed" for message in moderator_messages
                    ),
                    "moderator": True,
                }
            )
        return round_messages, layer_summaries

    def _run_parallel_layer(
        self,
        *,
        council_id: str,
        round_index: int,
        phase: CouncilPhase,
        roles: tuple[CouncilRole, ...],
        template: CouncilTemplate,
        shared_context: dict[str, Any],
        historical_messages: list[dict[str, Any]],
        current_round_messages: list[dict[str, Any]],
        turn_indices: dict[str, int],
    ) -> list[dict[str, Any]]:
        requests = []
        for role in roles:
            upstream_role_ids = template.workflow.upstream_role_ids(
                role.role_id,
                template.roles,
                phase_id=phase.phase_id,
                participant_role_ids=phase.participant_role_ids,
            )
            context_role_ids = template.workflow.context_role_ids(
                role.role_id,
                template.roles,
                phase_id=phase.phase_id,
                participant_role_ids=phase.participant_role_ids,
            )
            visible_messages = _visible_messages(
                historical_messages=historical_messages,
                current_round_messages=current_round_messages,
                upstream_role_ids=upstream_role_ids,
                context_role_ids=context_role_ids,
                workflow_version=template.workflow.version,
                context_policy=template.workflow.node_for_role(role.role_id).context_policy,
                round_index=round_index,
            )
            requests.append(
                CouncilTurnRequest(
                    council_id=council_id,
                    round_index=round_index,
                    turn_index=turn_indices[role.role_id],
                    phase=phase,
                    role=role,
                    shared_context=shared_context,
                    prior_messages=tuple(_prompt_message(message) for message in visible_messages),
                    reply_to_message_ids=_reply_ids(visible_messages),
                    workflow_node_id=template.workflow.node_id_for_role(role.role_id),
                    upstream_role_ids=upstream_role_ids,
                )
            )
        results: dict[str, dict[str, Any]] = {}
        with ThreadPoolExecutor(max_workers=min(len(requests), self.max_parallel_roles)) as executor:
            futures = {executor.submit(self.execute_turn, request): request for request in requests}
            for future in as_completed(futures):
                request = futures[future]
                results[request.role.role_id] = self._result_or_failure(future, request)
        return [results[request.role.role_id] for request in requests]

    def _run_round_robin_layer(
        self,
        *,
        council_id: str,
        round_index: int,
        phase: CouncilPhase,
        roles: tuple[CouncilRole, ...],
        template: CouncilTemplate,
        shared_context: dict[str, Any],
        historical_messages: list[dict[str, Any]],
        current_round_messages: list[dict[str, Any]],
        turn_indices: dict[str, int],
        include_all_current: bool = False,
    ) -> list[dict[str, Any]]:
        layer_messages: list[dict[str, Any]] = []
        for role in roles:
            upstream_role_ids = template.workflow.upstream_role_ids(
                role.role_id,
                template.roles,
                phase_id=phase.phase_id,
                participant_role_ids=phase.participant_role_ids,
            )
            context_role_ids = template.workflow.context_role_ids(
                role.role_id,
                template.roles,
                phase_id=phase.phase_id,
                participant_role_ids=phase.participant_role_ids,
            )
            visible_messages = _visible_messages(
                historical_messages=historical_messages,
                current_round_messages=[*current_round_messages, *layer_messages],
                upstream_role_ids=upstream_role_ids,
                context_role_ids=context_role_ids,
                workflow_version=template.workflow.version,
                context_policy=template.workflow.node_for_role(role.role_id).context_policy,
                round_index=round_index,
                include_current_peers=template.workflow.version == 1,
                include_all_current=include_all_current,
            )
            request = CouncilTurnRequest(
                council_id=council_id,
                round_index=round_index,
                turn_index=turn_indices[role.role_id],
                phase=phase,
                role=role,
                shared_context=shared_context,
                prior_messages=tuple(_prompt_message(message) for message in visible_messages),
                reply_to_message_ids=_reply_ids(visible_messages),
                workflow_node_id=template.workflow.node_id_for_role(role.role_id),
                upstream_role_ids=upstream_role_ids,
            )
            try:
                result = _decorate_result(self.execute_turn(request), request)
            except Exception as exc:
                result = _failed_message(request, exc)
            layer_messages.append(result)
        return layer_messages

    @staticmethod
    def _result_or_failure(future: Any, request: CouncilTurnRequest) -> dict[str, Any]:
        try:
            return _decorate_result(future.result(), request)
        except Exception as exc:
            return _failed_message(request, exc)


def _visible_messages(
    *,
    historical_messages: list[dict[str, Any]],
    current_round_messages: list[dict[str, Any]],
    upstream_role_ids: tuple[str, ...],
    context_role_ids: tuple[str, ...],
    workflow_version: int,
    context_policy: Any,
    round_index: int,
    include_current_peers: bool = False,
    include_all_current: bool = False,
) -> list[dict[str, Any]]:
    if workflow_version == 1:
        historical_visible = historical_messages
    elif context_policy.mode == "none":
        historical_visible = []
    else:
        accepted_roles = set(context_role_ids)
        minimum_round = max(1, round_index - context_policy.history_rounds)
        historical_visible = [
            message for message in historical_messages
            if str(message.get("agent_role") or "") in accepted_roles
            and int(message.get("round_index") or 0) >= minimum_round
        ]
        if context_policy.mode == "latest":
            historical_visible = _latest_messages_by_role(historical_visible)
    if include_all_current or include_current_peers:
        current_visible = current_round_messages
    else:
        visible_roles = set(context_role_ids if workflow_version >= 2 else upstream_role_ids)
        current_visible = [
            message
            for message in current_round_messages
            if str(message.get("agent_role") or "") in visible_roles
        ]
    visible = [*historical_visible, *current_visible]
    if workflow_version >= 2:
        visible = visible[-context_policy.max_messages :] if context_policy.max_messages else []
        if context_policy.mode == "claims_only":
            return [_claims_only_message(message) for message in visible]
        if context_policy.mode == "summary":
            return [_summary_message(message) for message in visible]
    return visible


def _latest_messages_by_role(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    latest: dict[str, dict[str, Any]] = {}
    for message in messages:
        latest[str(message.get("agent_role") or "")] = message
    return sorted(latest.values(), key=lambda message: int(message.get("turn_index") or 0))


def _claims_only_message(message: dict[str, Any]) -> dict[str, Any]:
    compact = _prompt_message(message)
    content = compact.get("content") if isinstance(compact.get("content"), dict) else {}
    assessments = content.get("candidate_assessments") if isinstance(content.get("candidate_assessments"), list) else []
    compact["content"] = {
        "claims": [
            claim
            for assessment in assessments
            if isinstance(assessment, dict)
            for claim in (assessment.get("claims") or [])
            if isinstance(claim, dict)
        ]
    }
    return compact


def _summary_message(message: dict[str, Any]) -> dict[str, Any]:
    compact = _prompt_message(message)
    content = compact.get("content") if isinstance(compact.get("content"), dict) else {}
    compact["content"] = {
        key: content.get(key)
        for key in ("overall_view", "debate_summary", "major_disagreements", "missing_evidence")
        if key in content
    }
    return compact


def _reply_ids(messages: list[dict[str, Any]]) -> tuple[str, ...]:
    return tuple(
        str(message["message_id"])
        for message in messages[-24:]
        if message.get("message_id") and message.get("status") == "completed"
    )


def _prompt_message(message: dict[str, Any]) -> dict[str, Any]:
    return {
        "message_id": message.get("message_id"),
        "round_index": message.get("round_index"),
        "phase_id": message.get("phase_id"),
        "message_type": message.get("message_type"),
        "agent_role": message.get("agent_role"),
        "stance": message.get("stance"),
        "status": message.get("status"),
        "content": message.get("content"),
    }


def _failed_message(request: CouncilTurnRequest, exc: Exception) -> dict[str, Any]:
    return {
        "message_id": f"failed:{request.council_id}:{request.round_index}:{request.role.role_id}",
        "debate_id": request.council_id,
        "round_index": request.round_index,
        "turn_index": request.turn_index,
        "phase_id": request.phase.phase_id,
        "message_type": request.phase.message_type,
        "agent_role": request.role.role_id,
        "stance": request.role.stance,
        "reply_to_message_ids": list(request.reply_to_message_ids),
        "workflow_node_id": request.workflow_node_id,
        "upstream_role_ids": list(request.upstream_role_ids),
        "status": "failed",
        "content": {},
        "error": f"{type(exc).__name__}: {exc}",
    }


def _decorate_result(result: dict[str, Any], request: CouncilTurnRequest) -> dict[str, Any]:
    result.setdefault("workflow_node_id", request.workflow_node_id)
    result.setdefault("upstream_role_ids", list(request.upstream_role_ids))
    return result
