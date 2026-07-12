from __future__ import annotations

import hashlib
import inspect
import json
import threading
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from time import perf_counter
from typing import Any, Iterable

from finbot.ai.openai_compatible import (
    LLMCompletion,
    OpenAICompatibleClient,
    OpenAICompatibleError,
    OpenAICompatibleProvider,
    provider_status,
)
from finbot.ai.governance import (
    AIBudgetGuard,
    AIBudgetPolicy,
    AIInvocationRecorder,
    prompt_input_hash,
)
from finbot.config.ai_sites import (
    AI_TASK_ID_DEBATE,
    AI_TASK_ID_TRADE_SYNTHESIS,
    AISitesConfigStore,
    render_prompt_template,
)
from finbot.council.models import CouncilChair, CouncilRole, CouncilTemplate
from finbot.council.orchestrator import CouncilOrchestrator, CouncilTurnRequest
from finbot.council.learning import CouncilLearningService
from finbot.storage.sqlite_store import SQLiteStore


IMMUTABLE_COUNCIL_POLICY_PROMPT = """不可覆盖的 FinBot Council 全局策略：
- 只能使用输入中的结构化证据、研究结论和公共行情。
- 不得虚构外部事实、价格、成交量、账户或交易执行结果。
- 所有关键结论必须保留 evidence_refs；证据不足时必须明确说明。
- 只能输出人工参考建议；不得调用或要求调用下单、撤单、转账或改仓接口。
- execution_allowed、order_api_allowed、private_exchange_api_allowed 必须为 false。
"""

USER_FACING_LANGUAGE_PROMPT = """用户可见输出语言与结构约束：
- JSON key、枚举值、symbol、provider、model 和 evidence_refs 保持既定英文契约。
- overall_view、arguments、counter_arguments、risk_flags、questions_for_other_agents、debate_summary、major_disagreements、missing_evidence、rationale、risk_warnings 和 invalidation_conditions 等自然语言字段必须使用简体中文。
- 主席输出必须包含 debate_summary、major_disagreements、missing_evidence；没有内容时返回空数组，不得省略字段。
- 不要输出逐步隐藏推理，只输出可供用户审阅的结论、依据、分歧和缺失证据。
"""

EXECUTABLE_DEBATE_MARKET_TYPES = frozenset({"linear", "future", "perpetual"})


@dataclass(frozen=True)
class DebateRole:
    role_id: str
    label: str
    stance: str
    objective: str


@dataclass(frozen=True)
class AIDebateConfig:
    loop_run_id: str
    research_pipeline_run_id: str | None
    operator_report_id: str | None
    user_query: str | None = None
    rounds: int = 3
    max_candidates: int = 3
    max_prompt_chars: int = 22000
    min_confidence: float = 0.58
    require_research_confirmation: bool = True
    template_id: str = "product_advisory"
    max_total_tokens_per_loop: int = 500_000
    max_cost_usd_per_loop: float | None = None
    max_output_tokens_per_call: int = 4_096
    director_plan: dict[str, Any] | None = None
    learning_enabled: bool = True


DEFAULT_DEBATE_ROLES = (
    DebateRole(
        "bull_researcher",
        "Bull Researcher",
        "bullish",
        "寻找支持方向性机会的证据，同时指出需要满足的确认条件。",
    ),
    DebateRole(
        "bear_researcher",
        "Bear Researcher",
        "bearish",
        "寻找反证、下行风险、新闻过期风险和市场冲突。",
    ),
    DebateRole(
        "market_structure",
        "Market Structure Analyst",
        "market",
        "聚焦多周期趋势、动量、波动、成交量、价差和关键价位。",
    ),
    DebateRole(
        "risk_controller",
        "Risk Controller",
        "risk",
        "从风险约束、仓位建议、失效条件和人工确认边界审查候选。",
    ),
)


class AIDebateCouncilRunner:
    def __init__(
        self,
        store: SQLiteStore,
        providers: dict[str, OpenAICompatibleProvider],
        ai_store: AISitesConfigStore,
        client: OpenAICompatibleClient | None = None,
        roles: tuple[DebateRole, ...] | None = None,
    ):
        self.store = store
        self.providers = providers
        self.ai_store = ai_store
        self.client = client or OpenAICompatibleClient()
        self.roles = roles
        self._attempts_lock = threading.Lock()
        self.budget_guard = AIBudgetGuard(store)
        self.invocation_recorder = AIInvocationRecorder(store, ai_store)
        self.learning = CouncilLearningService(store)

    def run_debate(self, candidates_report: dict[str, Any], config: AIDebateConfig) -> dict[str, Any]:
        candidates = _candidate_subset(candidates_report.get("candidates"), config.max_candidates)
        created_at = _now()
        debate_id = _hash_id("ai-debate", config.loop_run_id, created_at, [item.get("candidate_id") for item in candidates])
        template = self._council_template(config.template_id)
        council = {
            "debate_id": debate_id,
            "loop_run_id": config.loop_run_id,
            "research_pipeline_run_id": config.research_pipeline_run_id,
            "operator_report_id": config.operator_report_id,
            "template_id": template.template_id,
            "status": "running",
            "protocol": None,
            "provider": None,
            "model": None,
            "rounds": config.rounds,
            "summary": {"candidate_count": len(candidates), "message_count": 0},
            "round_summaries": [],
            "payload": {
                "user_query": config.user_query,
                "candidates": candidates,
                "messages": [],
                "template": template.to_dict(),
                "director_plan": config.director_plan,
            },
            "error": None,
            "created_at": created_at,
        }
        self.store.insert_ai_debate_council(council)
        if not candidates:
            return self._finish_council(council, status="empty", messages=[], error=None)
        if not template.enabled:
            return self._finish_council(council, status="skipped", messages=[], error="Council template disabled")
        if not self._task_context(AI_TASK_ID_DEBATE)["enabled"]:
            return self._finish_council(council, status="skipped", messages=[], error="AI debate task disabled")

        attempts: list[dict[str, Any]] = []
        shared_context = {
            "task": "phase8_ai_debate",
            "loop_run_id": config.loop_run_id,
            "research_pipeline_run_id": config.research_pipeline_run_id,
            "operator_report_id": config.operator_report_id,
            "user_query": config.user_query,
            "candidates": candidates,
            "policy": _policy_payload(),
        }
        shared_context["response_language"] = "zh-CN"

        def execute_turn(request: CouncilTurnRequest) -> dict[str, Any]:
            task = self._role_task_context(
                request.role,
                allocation_key=f"{config.loop_run_id}:{request.role.role_id}",
            )
            task["max_prompt_chars"] = config.max_prompt_chars
            role_payload = {
                **shared_context,
                "template_id": template.template_id,
                "round_index": request.round_index,
                "turn_index": request.turn_index,
                "phase": request.phase.to_dict(),
                "message_type": request.phase.message_type,
                "agent_role": request.role.role_id,
                "agent_label": request.role.display_name,
                "stance": request.role.stance,
                "objective": request.role.objective,
                "prior_messages": list(request.prior_messages),
                "reply_to_message_ids": list(request.reply_to_message_ids),
                "workflow": {
                    "node_id": request.workflow_node_id,
                    "upstream_role_ids": list(request.upstream_role_ids),
                },
                "selective_memory": self._selective_memory(
                    template_id=template.template_id,
                    role_id=request.role.role_id,
                    candidates=candidates,
                    user_query=config.user_query,
                    enabled=config.learning_enabled,
                ),
            }
            return self._complete_role_message(
                debate_id=debate_id,
                loop_run_id=config.loop_run_id,
                round_index=request.round_index,
                turn_index=request.turn_index,
                phase_id=request.phase.phase_id,
                message_type=request.phase.message_type,
                reply_to_message_ids=request.reply_to_message_ids,
                role_id=request.role.role_id,
                stance=request.role.stance,
                task=task,
                payload=role_payload,
                attempts=attempts,
                budget_policy=_budget_policy(config),
            )

        result = CouncilOrchestrator(execute_turn).run(
            council_id=debate_id,
            template=template,
            shared_context=shared_context,
            rounds=config.rounds,
        )
        messages = result["messages"]
        for message in messages:
            message.setdefault("loop_run_id", config.loop_run_id)
            message.setdefault("created_at", _now())
            self.store.insert_ai_debate_message(message)
        status = result["status"]
        error = None if status in {"ready_for_synthesis", "partial_ready_for_synthesis"} else "No Council round reached quorum"
        council["rounds"] = result["rounds_completed"]
        council["round_summaries"] = result["round_summaries"]
        council["payload"] = {
            "user_query": config.user_query,
            "candidates": candidates,
            "messages": messages,
            "attempts": attempts,
            "template": template.to_dict(),
            "round_summaries": result["round_summaries"],
            "director_plan": config.director_plan,
        }
        council["summary"] = {"candidate_count": len(candidates), **result["summary"]}
        return self._finish_council(council, status=status, messages=messages, error=error)

    def synthesize_decisions(
        self,
        debate_report: dict[str, Any],
        candidates_report: dict[str, Any],
        config: AIDebateConfig,
    ) -> dict[str, Any]:
        candidates = _candidate_subset(candidates_report.get("candidates"), config.max_candidates)
        debate_id = str(debate_report.get("debate_id") or "")
        if not debate_id:
            return {"status": "skipped", "reason": "missing debate_id", "ai_decisions": []}
        if not candidates:
            return {"status": "empty", "debate_id": debate_id, "ai_decisions": []}

        template = self._council_template(config.template_id)
        task = self._chair_task_context(
            template.chair,
            allocation_key=f"{config.loop_run_id}:{template.chair.role_id}",
        )
        task["max_prompt_chars"] = config.max_prompt_chars
        if not task["enabled"]:
            return {"status": "skipped", "debate_id": debate_id, "reason": "AI trade synthesis task disabled", "ai_decisions": []}

        debate_messages = [message for message in debate_report.get("messages", []) if isinstance(message, dict)]
        chair_input_role_ids = template.workflow.chair_input_role_ids(template.roles)
        synthesis_payload = {
            "task": "phase8_ai_trade_synthesis",
            "round_index": max(1, config.rounds) + 1,
            "agent_role": template.chair.role_id,
            "template_id": template.template_id,
            "loop_run_id": config.loop_run_id,
            "research_pipeline_run_id": config.research_pipeline_run_id,
            "operator_report_id": config.operator_report_id,
            "debate_id": debate_id,
            "user_query": config.user_query,
            "candidates": candidates,
            "debate_messages": [_message_for_prompt(message) for message in debate_messages],
            "workflow": {
                "node_id": template.workflow.node_id_for_role(template.chair.role_id),
                "upstream_role_ids": list(chair_input_role_ids),
            },
            "risk_gate": {
                "min_confidence": config.min_confidence,
                "require_research_confirmation": config.require_research_confirmation,
                "execution_allowed": False,
            },
            "selective_memory": self._selective_memory(
                template_id=template.template_id,
                role_id=None,
                candidates=candidates,
                user_query=config.user_query,
                enabled=config.learning_enabled,
            ),
        }
        synthesis_payload["response_language"] = "zh-CN"
        attempts: list[dict[str, Any]] = []
        chair_message = self._complete_role_message(
            debate_id=debate_id,
            loop_run_id=config.loop_run_id,
            round_index=max(1, config.rounds) + 1,
            turn_index=1,
            phase_id="chair_synthesis",
            message_type="decision",
            reply_to_message_ids=_latest_role_message_ids(debate_messages, chair_input_role_ids),
            role_id=template.chair.role_id,
            stance="neutral",
            task=task,
            payload=synthesis_payload,
            attempts=attempts,
            budget_policy=_budget_policy(config),
        )
        raw_decisions = _decisions_from_message(chair_message)
        if not raw_decisions:
            raw_decisions = [_fallback_decision(candidate, chair_message.get("error")) for candidate in candidates]
        decisions = [
            _apply_policy_gate(raw_decision, candidates, config, debate_id)
            for raw_decision in raw_decisions
            if isinstance(raw_decision, dict)
        ]
        created_at = _now()
        for decision in decisions:
            decision.update(
                {
                    "ai_site_id": chair_message.get("provider"),
                    "ai_model": chair_message.get("model"),
                    "prompt_version": chair_message.get("prompt_version"),
                    "experiment_id": chair_message.get("experiment_id"),
                    "variant_id": chair_message.get("variant_id"),
                    "ai_provenance": {
                        "ai_site_id": chair_message.get("provider"),
                        "ai_model": chair_message.get("model"),
                        "prompt_version": chair_message.get("prompt_version"),
                        "input_hash": chair_message.get("input_hash"),
                        "experiment_id": chair_message.get("experiment_id"),
                        "variant_id": chair_message.get("variant_id"),
                    },
                }
            )
            decision["created_at"] = created_at
            self.store.insert_ai_trade_decision(decision)
        council_payload = {
            "user_query": config.user_query,
            "candidates": candidates,
            "messages": [*debate_messages, chair_message],
            "chair": chair_message,
            "decisions": decisions,
            "attempts": attempts,
            "template": template.to_dict(),
            "round_summaries": debate_report.get("round_summaries", []),
            "director_plan": config.director_plan,
        }
        self.store.insert_ai_debate_council(
            {
                "debate_id": debate_id,
                "loop_run_id": config.loop_run_id,
                "research_pipeline_run_id": config.research_pipeline_run_id,
                "operator_report_id": config.operator_report_id,
                "template_id": template.template_id,
                "status": "passed" if chair_message["status"] == "completed" else "partial",
                "protocol": chair_message.get("protocol"),
                "provider": chair_message.get("provider"),
                "model": chair_message.get("model"),
                "rounds": config.rounds,
                "round_summaries": debate_report.get("round_summaries", []),
                "summary": {
                    "candidate_count": len(candidates),
                    "decision_count": len(decisions),
                    "chair_status": chair_message["status"],
                    "message_count": len(debate_messages) + 1,
                    "actions": _action_counts(decisions),
                },
                "payload": council_payload,
                "error": chair_message.get("error"),
                "created_at": debate_report.get("created_at") or created_at,
            }
        )
        if config.learning_enabled:
            try:
                learning = self.learning.refresh_debate(debate_id)
            except Exception as exc:
                learning = {"status": "failed", "error": _redact_error(str(exc)), "policy": {"automatic_prompt_changes_allowed": False}}
        else:
            learning = {"status": "disabled", "policy": {"automatic_prompt_changes_allowed": False}}
        return {
            "status": "passed" if chair_message["status"] == "completed" else "partial",
            "debate_id": debate_id,
            "chair_message": chair_message,
            "decision_count": len(decisions),
            "ai_decisions": decisions,
            "policy": _policy_payload(),
            "round_summaries": debate_report.get("round_summaries", []),
            "learning": learning,
        }

    def _selective_memory(
        self,
        *,
        template_id: str,
        role_id: str | None,
        candidates: list[dict[str, Any]],
        user_query: str | None,
        enabled: bool = True,
    ) -> dict[str, Any]:
        if not enabled:
            return {"status": "disabled", "count": 0, "items": []}
        candidate = candidates[0] if candidates else {}
        try:
            return self.learning.retrieve(
                template_id=template_id,
                role_id=role_id,
                product_id=_optional_text(candidate.get("product_id") or candidate.get("candidate_id")),
                symbol=_optional_text(candidate.get("normalized_symbol") or candidate.get("symbol")),
                market_type=_optional_text(candidate.get("market_type")),
                topics=[user_query] if user_query else [],
                limit=4,
                max_chars=4_000,
            )
        except Exception as exc:
            return {
                "status": "unavailable",
                "count": 0,
                "items": [],
                "error": _redact_error(str(exc)),
                "policy": {"automatic_prompt_changes_allowed": False},
            }

    def _complete_role_message(
        self,
        debate_id: str,
        loop_run_id: str,
        round_index: int,
        turn_index: int,
        phase_id: str,
        message_type: str,
        reply_to_message_ids: tuple[str, ...],
        role_id: str,
        stance: str,
        task: dict[str, Any],
        payload: dict[str, Any],
        attempts: list[dict[str, Any]],
        budget_policy: AIBudgetPolicy,
    ) -> dict[str, Any]:
        created_at = _now()
        timer = perf_counter()
        user_prompt = render_prompt_template(
            task["prompt"].user_prompt_template,
            payload_json=_bounded_json(payload, task["max_prompt_chars"]),
            target_type="ai_debate",
            target_id=role_id,
        )
        system_prompt = task["prompt"].system_prompt
        if payload.get("response_language") == "zh-CN":
            system_prompt = f"{system_prompt}\n\n{USER_FACING_LANGUAGE_PROMPT.strip()}"
        input_hash = prompt_input_hash(system_prompt, user_prompt)
        prompt_version = self.invocation_recorder.register_prompt(
            task_id=task["task_id"],
            role_id=role_id,
            system_prompt=system_prompt,
            user_prompt_template=task["prompt"].user_prompt_template,
        )
        message_base = {
            "message_id": _hash_id("ai-debate-message", debate_id, role_id, round_index, phase_id, turn_index),
            "debate_id": debate_id,
            "loop_run_id": loop_run_id,
            "round_index": round_index,
            "turn_index": turn_index,
            "phase_id": phase_id,
            "message_type": message_type,
            "reply_to_message_ids": list(reply_to_message_ids),
            "agent_role": role_id,
            "stance": stance,
            "provider": None,
            "protocol": task["protocol"],
            "model": None,
            "status": "failed",
            "content": {},
            "error": None,
            "usage": {},
            "duration_ms": None,
            "prompt_hash": input_hash,
            "prompt_version": prompt_version,
            "input_hash": input_hash,
            "experiment_id": task.get("experiment_id"),
            "variant_id": task.get("variant_id"),
            "estimated_cost_usd": None,
            "cost_status": "unknown",
            "created_at": created_at,
        }
        local_attempts: list[dict[str, Any]] = []
        for attempt_index, provider in enumerate(task["providers"], start=1):
            status = provider_status(provider, task["protocol"])
            if not status.configured:
                attempt = {
                    "provider": provider.name,
                    "role": role_id,
                    "round_index": round_index,
                    "phase_id": phase_id,
                    "status": "skipped",
                    "missing": status.missing,
                }
                local_attempts.append(attempt)
                self.invocation_recorder.record(
                    loop_run_id=loop_run_id,
                    debate_id=debate_id,
                    message_id=message_base["message_id"],
                    task_id=task["task_id"],
                    role_id=role_id,
                    site_id=provider.name,
                    protocol=task["protocol"],
                    model=provider.model_for(task["protocol"]),
                    prompt_version=prompt_version,
                    input_hash=input_hash,
                    experiment_id=task.get("experiment_id"),
                    variant_id=task.get("variant_id"),
                    status="misconfigured",
                    usage={},
                    duration_ms=int((perf_counter() - timer) * 1000),
                    error=f"missing: {', '.join(status.missing)}",
                    attempt_index=attempt_index,
                )
                continue
            pricing = self.ai_store.site_pricing(
                provider.name,
                model=provider.model_for(task["protocol"]),
            )
            permit = self.budget_guard.acquire(
                loop_run_id=loop_run_id,
                site_id=provider.name,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                pricing=pricing,
                policy=budget_policy,
            )
            if not permit.allowed:
                local_attempts.append(
                    {
                        "provider": provider.name,
                        "role": role_id,
                        "round_index": round_index,
                        "phase_id": phase_id,
                        "status": "budget_blocked",
                        "error": permit.reason,
                    }
                )
                self.invocation_recorder.record(
                    loop_run_id=loop_run_id,
                    debate_id=debate_id,
                    message_id=message_base["message_id"],
                    task_id=task["task_id"],
                    role_id=role_id,
                    site_id=provider.name,
                    protocol=task["protocol"],
                    model=provider.model_for(task["protocol"]),
                    prompt_version=prompt_version,
                    input_hash=input_hash,
                    experiment_id=task.get("experiment_id"),
                    variant_id=task.get("variant_id"),
                    status="budget_blocked",
                    usage={},
                    duration_ms=int((perf_counter() - timer) * 1000),
                    error=permit.reason,
                    attempt_index=attempt_index,
                )
                continue
            completion: LLMCompletion | None = None
            attempt_timer = perf_counter()
            try:
                completion = self._invoke_completion(
                    provider=provider,
                    protocol=task["protocol"],
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    require_json=True,
                    max_output_tokens=permit.max_output_tokens,
                    reasoning_effort=task["reasoning_effort"],
                )
                content = _parse_json_object(completion.content)
                if phase_id == "chair_synthesis":
                    content = _normalize_chair_content(
                        content,
                        [item for item in payload.get("candidates", []) if isinstance(item, dict)],
                    )
                duration_ms = int((perf_counter() - attempt_timer) * 1000)
                invocation = self.invocation_recorder.record(
                    loop_run_id=loop_run_id,
                    debate_id=debate_id,
                    message_id=message_base["message_id"],
                    task_id=task["task_id"],
                    role_id=role_id,
                    site_id=completion.provider,
                    protocol=completion.protocol,
                    model=completion.model,
                    prompt_version=prompt_version,
                    input_hash=input_hash,
                    experiment_id=task.get("experiment_id"),
                    variant_id=task.get("variant_id"),
                    status="completed",
                    usage=completion.usage,
                    duration_ms=duration_ms,
                    error=None,
                    attempt_index=attempt_index,
                )
                self.budget_guard.release(
                    permit,
                    total_tokens=int(invocation.get("total_tokens") or 0),
                    estimated_cost_usd=invocation.get("estimated_cost_usd"),
                )
                message = {
                    **message_base,
                    "provider": completion.provider,
                    "protocol": completion.protocol,
                    "model": completion.model,
                    "status": "completed",
                    "content": content,
                    "usage": completion.usage,
                    "duration_ms": int((perf_counter() - timer) * 1000),
                    "estimated_cost_usd": invocation.get("estimated_cost_usd"),
                    "cost_status": invocation.get("cost_status"),
                    "attempts": local_attempts,
                }
                self._append_attempts(attempts, local_attempts)
                self.store.insert_ai_debate_message(message)
                return message
            except (OpenAICompatibleError, ValueError) as exc:
                error = _redact_error(str(exc))
                usage = completion.usage if completion is not None else {}
                invocation = self.invocation_recorder.record(
                    loop_run_id=loop_run_id,
                    debate_id=debate_id,
                    message_id=message_base["message_id"],
                    task_id=task["task_id"],
                    role_id=role_id,
                    site_id=provider.name,
                    protocol=task["protocol"],
                    model=provider.model_for(task["protocol"]),
                    prompt_version=prompt_version,
                    input_hash=input_hash,
                    experiment_id=task.get("experiment_id"),
                    variant_id=task.get("variant_id"),
                    status="failed",
                    usage=usage,
                    duration_ms=int((perf_counter() - attempt_timer) * 1000),
                    error=error,
                    attempt_index=attempt_index,
                )
                self.budget_guard.release(
                    permit,
                    total_tokens=int(invocation.get("total_tokens") or 0),
                    estimated_cost_usd=invocation.get("estimated_cost_usd"),
                )
                local_attempts.append(
                    {
                        "provider": provider.name,
                        "role": role_id,
                        "round_index": round_index,
                        "phase_id": phase_id,
                        "status": "failed",
                        "error": error,
                    }
                )
        self._append_attempts(attempts, local_attempts)
        message = {
            **message_base,
            "error": f"All providers failed for role {role_id}",
            "attempts": local_attempts,
            "duration_ms": int((perf_counter() - timer) * 1000),
        }
        self.store.insert_ai_debate_message(message)
        return message

    def _invoke_completion(self, **kwargs: Any) -> LLMCompletion:
        parameters = inspect.signature(self.client.complete).parameters
        accepts_kwargs = any(parameter.kind == inspect.Parameter.VAR_KEYWORD for parameter in parameters.values())
        if not accepts_kwargs:
            kwargs = {key: value for key, value in kwargs.items() if key in parameters}
        return self.client.complete(**kwargs)

    def _task_context(self, task_id: str) -> dict[str, Any]:
        return self._task_context_with_overrides(task_id)

    def _role_task_context(self, role: CouncilRole, allocation_key: str) -> dict[str, Any]:
        return self._task_context_with_overrides(
            AI_TASK_ID_DEBATE,
            site_id=role.site_id,
            protocol=role.protocol,
            model=role.model,
            reasoning_effort=role.reasoning_effort,
            fallback_site_ids=role.fallback_site_ids,
            system_prompt=role.system_prompt,
            user_prompt_template=role.user_prompt_template,
            allocation_key=allocation_key,
        )

    def _chair_task_context(self, chair: CouncilChair, allocation_key: str) -> dict[str, Any]:
        return self._task_context_with_overrides(
            AI_TASK_ID_TRADE_SYNTHESIS,
            site_id=chair.site_id,
            protocol=chair.protocol,
            model=chair.model,
            reasoning_effort=chair.reasoning_effort,
            fallback_site_ids=chair.fallback_site_ids,
            system_prompt=chair.system_prompt,
            user_prompt_template=chair.user_prompt_template,
            allocation_key=allocation_key,
        )

    def _task_context_with_overrides(
        self,
        task_id: str,
        *,
        site_id: str | None = None,
        protocol: str | None = None,
        model: str | None = None,
        reasoning_effort: str = "provider_default",
        fallback_site_ids: tuple[str, ...] = (),
        system_prompt: str | None = None,
        user_prompt_template: str | None = None,
        allocation_key: str | None = None,
    ) -> dict[str, Any]:
        binding = self.ai_store.task_binding(task_id)
        prompt = self.ai_store.prompt(task_id)
        variant = self.ai_store.experiment_variant(task_id, allocation_key) if allocation_key else None
        selected_protocol = (variant or {}).get("protocol") or protocol or binding.protocol
        selected_site_id = (variant or {}).get("site_id") or site_id or binding.site_id
        if variant and variant.get("site_id"):
            selected_model = variant.get("model")
        else:
            selected_model = (variant or {}).get("model") or model or binding.model
        selected_fallbacks = fallback_site_ids or binding.fallback_site_ids
        providers = dict(self.providers)
        if selected_site_id and selected_model and selected_site_id in providers:
            provider = providers[selected_site_id]
            providers[selected_site_id] = replace(
                provider,
                chat_model=selected_model if selected_protocol == "chat" else provider.chat_model,
                responses_model=selected_model if selected_protocol == "responses" else provider.responses_model,
            )
        provider_order = tuple(
            dict.fromkeys(
                [
                    *([selected_site_id] if selected_site_id else []),
                    *selected_fallbacks,
                ]
            )
        ) or tuple(providers.keys())
        ordered_providers = [providers[name] for name in provider_order if name in providers]
        role_prompt = system_prompt.strip() if system_prompt else ""
        combined_system_prompt = "\n\n".join(
            part
            for part in (
                prompt.system_prompt.strip(),
                role_prompt,
                (variant or {}).get("system_prompt_append"),
                IMMUTABLE_COUNCIL_POLICY_PROMPT.strip(),
            )
            if part
        )
        return {
            "task_id": task_id,
            "enabled": binding.enabled,
            "protocol": selected_protocol,
            "prompt": replace(
                prompt,
                system_prompt=combined_system_prompt,
                user_prompt_template=(variant or {}).get("user_prompt_template") or user_prompt_template or prompt.user_prompt_template,
            ),
            "providers": ordered_providers,
            "reasoning_effort": reasoning_effort,
            "max_prompt_chars": 22000,
            "experiment_id": (variant or {}).get("experiment_id"),
            "variant_id": (variant or {}).get("variant_id"),
        }

    def _council_template(self, template_id: str) -> CouncilTemplate:
        template = self.ai_store.council_template(template_id)
        if self.roles is None:
            return template
        payload = template.to_dict()
        payload["roles"] = [
            {
                "role_id": role.role_id,
                "display_name": role.label,
                "stance": role.stance,
                "objective": role.objective,
                "enabled": True,
                "order": index * 10,
            }
            for index, role in enumerate(self.roles, start=1)
        ]
        payload.pop("workflow", None)
        return CouncilTemplate.from_payload(payload)

    def _append_attempts(self, target: list[dict[str, Any]], values: list[dict[str, Any]]) -> None:
        if not values:
            return
        with self._attempts_lock:
            target.extend(values)

    def _finish_council(
        self,
        council: dict[str, Any],
        status: str,
        messages: list[dict[str, Any]],
        error: str | None,
    ) -> dict[str, Any]:
        council = {**council, "status": status, "error": error}
        self.store.insert_ai_debate_council(council)
        return {
            "status": status,
            "debate_id": council["debate_id"],
            "created_at": council["created_at"],
            "summary": council["summary"],
            "messages": messages,
            "template_id": council.get("template_id"),
            "round_summaries": council.get("round_summaries", []),
            "error": error,
            "policy": _policy_payload(),
        }


def _candidate_subset(value: Any, limit: int) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    candidates = [item for item in value if isinstance(item, dict)]
    maximum = max(1, limit)
    selected: list[dict[str, Any]] = []
    selected_ids: set[int] = set()
    selected_symbols: set[str] = set()

    def add_unique(*, executable_only: bool, confirmed_only: bool) -> None:
        for candidate in candidates:
            if len(selected) >= maximum:
                return
            if id(candidate) in selected_ids:
                continue
            market_type = str(candidate.get("market_type") or "").strip().lower()
            if executable_only and market_type not in EXECUTABLE_DEBATE_MARKET_TYPES:
                continue
            if confirmed_only and not _has_research_confirmation(candidate):
                continue
            symbol = _candidate_symbol_key(candidate)
            if symbol in selected_symbols:
                continue
            selected.append(candidate)
            selected_ids.add(id(candidate))
            selected_symbols.add(symbol)

    add_unique(executable_only=True, confirmed_only=True)
    add_unique(executable_only=True, confirmed_only=False)
    add_unique(executable_only=False, confirmed_only=True)
    add_unique(executable_only=False, confirmed_only=False)
    for candidate in candidates:
        if len(selected) >= maximum:
            break
        if id(candidate) not in selected_ids:
            selected.append(candidate)
            selected_ids.add(id(candidate))
    return selected


def _candidate_symbol_key(candidate: dict[str, Any]) -> str:
    value = candidate.get("normalized_symbol") or candidate.get("symbol") or candidate.get("candidate_id")
    return "".join(character for character in str(value or "").upper() if character.isalnum())


def _decisions_from_message(message: dict[str, Any]) -> list[dict[str, Any]]:
    content = message.get("content") if isinstance(message.get("content"), dict) else {}
    decisions = content.get("decisions") if isinstance(content.get("decisions"), list) else []
    return [decision for decision in decisions if isinstance(decision, dict)]


def _fallback_decision(candidate: dict[str, Any], reason: str | None) -> dict[str, Any]:
    levels = candidate.get("levels") if isinstance(candidate.get("levels"), dict) else {}
    return {
        "candidate_id": candidate.get("candidate_id"),
        "symbol": candidate.get("symbol"),
        "provider": candidate.get("provider"),
        "market_type": candidate.get("market_type"),
        "action": candidate.get("market_action") or "WATCH",
        "confidence": candidate.get("market_confidence") or 0.0,
        "score": candidate.get("score") or 0.0,
        "entry_reference": levels.get("entry_reference"),
        "target_price": levels.get("target_price"),
        "invalidation_price": levels.get("invalidation_price"),
        "position_sizing": {},
        "rationale": ["AI 合成未返回可用决策，保留确定性候选作为人工观察。"],
        "risk_warnings": [reason or "AI synthesis unavailable"],
        "evidence_refs": candidate.get("evidence_refs") or [],
    }


def _normalize_chair_content(
    content: dict[str, Any],
    candidates: list[dict[str, Any]],
) -> dict[str, Any]:
    normalized = dict(content)
    summaries = _string_list(normalized.get("debate_summary"))
    if not summaries:
        summaries = ["主席未返回结构化摘要，结果仅保留为人工复核材料。"]

    disagreements = _string_list(normalized.get("major_disagreements"))
    if not disagreements:
        disagreements = [
            summary
            for summary in summaries
            if any(term in summary.lower() for term in ("分歧", "冲突", "conflict", "inconsistent", "mixed"))
        ]

    missing_evidence = _string_list(normalized.get("missing_evidence"))
    if not missing_evidence:
        for candidate in candidates:
            if not _has_research_confirmation(candidate):
                symbol = str(candidate.get("symbol") or candidate.get("normalized_symbol") or "该候选")
                missing_evidence.append(f"{symbol} 缺少已确认且可交叉验证的研究证据。")
    if str(normalized.get("council_status") or "").lower() == "needs-followup" and not missing_evidence:
        missing_evidence.append("委员会要求继续补充研究证据。")

    normalized["debate_summary"] = list(dict.fromkeys(summaries))
    normalized["major_disagreements"] = list(dict.fromkeys(disagreements))
    normalized["missing_evidence"] = list(dict.fromkeys(missing_evidence))
    normalized["response_language"] = "zh-CN"
    return normalized


def _apply_policy_gate(
    raw_decision: dict[str, Any],
    candidates: list[dict[str, Any]],
    config: AIDebateConfig,
    debate_id: str,
) -> dict[str, Any]:
    candidate = _candidate_by_id(candidates, raw_decision.get("candidate_id")) or _candidate_by_symbol(candidates, raw_decision.get("symbol"))
    action = str(raw_decision.get("action") or "WATCH").upper()
    if action not in {"BUY", "SELL", "HOLD", "WATCH"}:
        action = "WATCH"
    confidence = _clamp(_float(raw_decision.get("confidence"), 0.0), 0.0, 1.0)
    gate_reasons = []
    if action in {"BUY", "SELL"}:
        if confidence < config.min_confidence:
            gate_reasons.append(f"AI confidence {confidence:.2f} below threshold {config.min_confidence:.2f}")
            action = "WATCH"
        if config.require_research_confirmation and not _has_research_confirmation(candidate):
            gate_reasons.append("directional advice requires matched research confirmation")
            action = "WATCH"
        if action in {"BUY", "SELL"} and not _market_confirmation_supports_action(candidate, action):
            gate_reasons.append("cross-venue market confirmation does not support the selected direction")
            action = "WATCH"
        if not _has_trade_levels(raw_decision, candidate):
            gate_reasons.append("directional advice requires target and invalidation levels")
            action = "WATCH"
    levels = candidate.get("levels") if isinstance(candidate, dict) and isinstance(candidate.get("levels"), dict) else {}
    position_sizing = raw_decision.get("position_sizing") if isinstance(raw_decision.get("position_sizing"), dict) else {}
    if action not in {"BUY", "SELL"}:
        position_sizing = {
            **position_sizing,
            "risk_per_trade_pct": 0.0,
            "max_position_notional_pct": 0.0,
            "sizing_policy": "advisory-only-no-position-for-hold-watch",
        }
    else:
        position_sizing = {
            "risk_per_trade_pct": _clamp(_float(position_sizing.get("risk_per_trade_pct"), 0.5), 0.0, 10.0),
            "max_position_notional_pct": _clamp(_float(position_sizing.get("max_position_notional_pct"), 5.0), 0.0, 100.0),
            "sizing_policy": str(position_sizing.get("sizing_policy") or "advisory-only-user-must-confirm"),
        }
    symbol = str(raw_decision.get("symbol") or (candidate or {}).get("symbol") or "")
    provider = raw_decision.get("provider") or (candidate or {}).get("provider")
    market_type = raw_decision.get("market_type") or (candidate or {}).get("market_type")
    rationale = _string_list(raw_decision.get("rationale"))[:8]
    risk_warnings = [*_string_list(raw_decision.get("risk_warnings"))[:8], *gate_reasons]
    score = _float(raw_decision.get("score"), confidence * 100)
    entry_reference = _first_number(raw_decision.get("entry_reference"), levels.get("entry_reference"))
    target_price = _first_number(raw_decision.get("target_price"), levels.get("target_price"))
    invalidation_price = _first_number(raw_decision.get("invalidation_price"), levels.get("invalidation_price"))
    if action not in {"BUY", "SELL"}:
        target_price = None
        invalidation_price = None
    payload = {
        "decision_id": _hash_id("ai-trade-decision", config.loop_run_id, debate_id, provider, market_type, symbol, raw_decision.get("candidate_id")),
        "loop_run_id": config.loop_run_id,
        "debate_id": debate_id,
        "source_report_id": config.operator_report_id,
        "candidate_id": raw_decision.get("candidate_id") or (candidate or {}).get("candidate_id"),
        "symbol": symbol,
        "normalized_symbol": raw_decision.get("normalized_symbol") or (candidate or {}).get("normalized_symbol") or symbol,
        "provider": provider,
        "market_type": market_type,
        "action": action,
        "status": "candidate" if action in {"BUY", "SELL"} else "hold" if action == "HOLD" else "watch",
        "confidence": round(confidence, 4),
        "score": round(score + (20 if action in {"BUY", "SELL"} else 0), 4),
        "horizon": raw_decision.get("horizon") or (candidate or {}).get("horizon"),
        "entry_reference": entry_reference,
        "target_price": target_price,
        "invalidation_price": invalidation_price,
        "position_sizing": position_sizing,
        "rationale": rationale or ["AI debate synthesis returned no rationale."],
        "risk_warnings": risk_warnings,
        "evidence_refs": _string_list(raw_decision.get("evidence_refs") or (candidate or {}).get("evidence_refs")),
        "invalidation_conditions": _string_list(raw_decision.get("invalidation_conditions"))[:6],
        "research_context": (candidate or {}).get("research_context") or {},
        "policy": {
            **_policy_payload(),
            "risk_gate_reasons": gate_reasons,
            "min_confidence": config.min_confidence,
            "require_research_confirmation": config.require_research_confirmation,
        },
    }
    return payload


def _candidate_by_id(candidates: list[dict[str, Any]], candidate_id: Any) -> dict[str, Any] | None:
    if not candidate_id:
        return None
    for candidate in candidates:
        if str(candidate.get("candidate_id")) == str(candidate_id):
            return candidate
    return None


def _candidate_by_symbol(candidates: list[dict[str, Any]], symbol: Any) -> dict[str, Any] | None:
    if not symbol:
        return None
    normalized = str(symbol).replace("-", "").replace("_", "").upper()
    for candidate in candidates:
        candidate_symbol = str(candidate.get("normalized_symbol") or candidate.get("symbol") or "").replace("-", "").replace("_", "").upper()
        if candidate_symbol == normalized:
            return candidate
    return None


def _has_research_confirmation(candidate: dict[str, Any] | None) -> bool:
    if not candidate:
        return False
    research_context = candidate.get("research_context") if isinstance(candidate.get("research_context"), dict) else {}
    matched_count = int(research_context.get("matched_items_count") or 0)
    status = str(research_context.get("status") or "").lower()
    if status == "market-confirmed":
        confirmation = research_context.get("market_confirmation") if isinstance(research_context.get("market_confirmation"), dict) else {}
        confirmed_action = str(confirmation.get("action") or "").upper()
        candidate_action = str(candidate.get("market_action") or "").upper()
        return (
            confirmation.get("valid") is True
            and int(confirmation.get("provider_count") or 0) >= 2
            and confirmed_action in {"BUY", "SELL"}
            and confirmed_action == candidate_action
            and _float(confirmation.get("minimum_confidence"), 0.0) >= 0.60
            and _float(confirmation.get("maximum_price_divergence_pct"), 100.0) <= 1.0
        )
    confirmed_statuses = {"passed", "confirmed", "ready", "approved", "watch-approved", "active-watch"}
    if matched_count <= 0 or status not in confirmed_statuses:
        return False
    matched_items = research_context.get("matched_items") if isinstance(research_context.get("matched_items"), list) else []
    item_statuses = {
        str(item.get("status") or "").lower()
        for item in matched_items
        if isinstance(item, dict) and item.get("status")
    }
    return not item_statuses or item_statuses <= confirmed_statuses


def _market_confirmation_supports_action(candidate: dict[str, Any] | None, action: str) -> bool:
    if not candidate:
        return True
    research_context = candidate.get("research_context") if isinstance(candidate.get("research_context"), dict) else {}
    if str(research_context.get("status") or "").lower() != "market-confirmed":
        return True
    confirmation = research_context.get("market_confirmation") if isinstance(research_context.get("market_confirmation"), dict) else {}
    return str(confirmation.get("action") or "").upper() == action


def _has_trade_levels(raw_decision: dict[str, Any], candidate: dict[str, Any] | None) -> bool:
    levels = candidate.get("levels") if isinstance(candidate, dict) and isinstance(candidate.get("levels"), dict) else {}
    target = _first_number(raw_decision.get("target_price"), levels.get("target_price"))
    invalidation = _first_number(raw_decision.get("invalidation_price"), levels.get("invalidation_price"))
    return target is not None and target > 0 and invalidation is not None and invalidation > 0


def _message_for_prompt(message: dict[str, Any]) -> dict[str, Any]:
    return {
        "message_id": message.get("message_id"),
        "round_index": message.get("round_index"),
        "turn_index": message.get("turn_index"),
        "phase_id": message.get("phase_id"),
        "message_type": message.get("message_type"),
        "agent_role": message.get("agent_role"),
        "stance": message.get("stance"),
        "status": message.get("status"),
        "reply_to_message_ids": message.get("reply_to_message_ids"),
        "workflow_node_id": message.get("workflow_node_id"),
        "upstream_role_ids": message.get("upstream_role_ids"),
        "content": message.get("content"),
        "error": message.get("error"),
    }


def _latest_role_message_ids(
    messages: list[dict[str, Any]],
    role_ids: tuple[str, ...],
) -> tuple[str, ...]:
    latest_by_role: dict[str, str] = {}
    accepted_roles = set(role_ids)
    for message in messages:
        role_id = str(message.get("agent_role") or "")
        message_id = str(message.get("message_id") or "")
        if role_id in accepted_roles and message_id and message.get("status") == "completed":
            latest_by_role[role_id] = message_id
    return tuple(latest_by_role[role_id] for role_id in role_ids if role_id in latest_by_role)


def _policy_payload() -> dict[str, Any]:
    return {
        "execution_allowed": False,
        "order_api_allowed": False,
        "private_exchange_api_allowed": False,
        "human_confirmation_required": True,
        "advisory_only": True,
    }


def _budget_policy(config: AIDebateConfig) -> AIBudgetPolicy:
    return AIBudgetPolicy(
        max_total_tokens_per_loop=max(1, int(config.max_total_tokens_per_loop)),
        max_cost_usd_per_loop=(
            None if config.max_cost_usd_per_loop is None
            else max(0.0, float(config.max_cost_usd_per_loop))
        ),
        max_output_tokens_per_call=max(64, int(config.max_output_tokens_per_call)),
    )


def _bounded_json(payload: dict[str, Any], max_chars: int) -> str:
    normalized = json.loads(json.dumps(payload, ensure_ascii=False, default=str))
    text = json.dumps(normalized, ensure_ascii=False)
    if len(text) <= max_chars:
        return text

    compact = dict(normalized)
    compact["context_truncated"] = True
    if isinstance(compact.get("candidates"), list):
        compact["candidates"] = [_compact_candidate(candidate) for candidate in compact["candidates"]]
    for key in ("prior_messages", "debate_messages"):
        if isinstance(compact.get(key), list):
            compact[key] = [_compact_prompt_message(message) for message in compact[key]]

    for message_limit, string_limit in ((32, 1600), (24, 800), (16, 400), (8, 200), (4, 80)):
        candidate = _limit_message_context(compact, message_limit)
        candidate = _truncate_json_strings(candidate, string_limit)
        text = json.dumps(candidate, ensure_ascii=False)
        if len(text) <= max_chars:
            return text

    essential_keys = (
        "task",
        "loop_run_id",
        "debate_id",
        "template_id",
        "round_index",
        "turn_index",
        "phase",
        "message_type",
        "agent_role",
        "agent_label",
        "stance",
        "objective",
        "candidates",
        "risk_gate",
        "policy",
    )
    essential = {key: compact[key] for key in essential_keys if key in compact}
    essential["context_truncated"] = True
    for string_limit in (120, 60, 24):
        candidate = _truncate_json_strings(essential, string_limit)
        text = json.dumps(candidate, ensure_ascii=False)
        if len(text) <= max_chars:
            return text

    minimal = {
        "task": compact.get("task"),
        "agent_role": compact.get("agent_role"),
        "round_index": compact.get("round_index"),
        "context_truncated": True,
    }
    text = json.dumps(minimal, ensure_ascii=False)
    return text if len(text) <= max_chars else "{}"


def _compact_prompt_message(message: Any) -> dict[str, Any]:
    if not isinstance(message, dict):
        return {"content": str(message)}
    keys = (
        "message_id",
        "round_index",
        "turn_index",
        "phase_id",
        "message_type",
        "agent_role",
        "stance",
        "status",
        "reply_to_message_ids",
        "content",
        "error",
    )
    return {key: message.get(key) for key in keys if key in message}


def _limit_message_context(payload: dict[str, Any], message_limit: int) -> dict[str, Any]:
    limited = dict(payload)
    for key in ("prior_messages", "debate_messages"):
        messages = limited.get(key)
        if isinstance(messages, list) and len(messages) > message_limit:
            limited[key] = messages[-message_limit:]
    return limited


def _truncate_json_strings(value: Any, max_length: int) -> Any:
    if isinstance(value, str):
        if len(value) <= max_length:
            return value
        return f"{value[:max_length]}...[truncated]"
    if isinstance(value, list):
        return [_truncate_json_strings(item, max_length) for item in value]
    if isinstance(value, dict):
        return {key: _truncate_json_strings(item, max_length) for key, item in value.items()}
    return value


def _compact_candidate(candidate: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "candidate_id",
        "source",
        "symbol",
        "normalized_symbol",
        "provider",
        "market_type",
        "market_action",
        "market_confidence",
        "score",
        "horizon",
        "levels",
        "metrics",
        "research_context",
        "evidence_refs",
    )
    return {key: candidate.get(key) for key in keys if key in candidate}


def _parse_json_object(content: str) -> dict[str, Any]:
    try:
        value = json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("AI response is not a JSON object")
        value = json.loads(content[start : end + 1])
    if not isinstance(value, dict):
        raise ValueError("AI response root must be an object")
    return value


def _action_counts(decisions: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for decision in decisions:
        action = str(decision.get("action") or "UNKNOWN")
        counts[action] = counts.get(action, 0) + 1
    return counts


def _string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, (list, tuple)):
        return [str(item).strip() for item in value if str(item).strip()]
    return [str(value).strip()] if str(value).strip() else []


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    clean = str(value).strip()
    return clean or None


def _first_number(*values: Any) -> float | None:
    for value in values:
        try:
            if value is not None and value != "":
                return float(value)
        except (TypeError, ValueError):
            continue
    return None


def _float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def _hash_id(*parts: Any) -> str:
    text = json.dumps(parts, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:32]


def _redact_error(value: str) -> str:
    return value.replace("\n", " ").strip()[:500]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
