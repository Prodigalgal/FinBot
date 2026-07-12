from __future__ import annotations

import json
from dataclasses import dataclass, replace
from time import perf_counter
from typing import Any

from finbot.ai.governance import (
    AIBudgetGuard,
    AIBudgetPolicy,
    AIInvocationRecorder,
    prompt_input_hash,
)
from finbot.ai.openai_compatible import (
    OpenAICompatibleClient,
    OpenAICompatibleError,
    OpenAICompatibleProvider,
    provider_status,
)
from finbot.config.ai_sites import AI_TASK_ID_EXECUTION_ROBOT, AISitesConfigStore, render_prompt_template
from finbot.instruments.models import stable_id
from finbot.storage.sqlite_store import SQLiteStore


DIRECTIONAL_ACTIONS = frozenset({"BUY", "SELL"})
SAFE_RISK_STATUSES = frozenset({"passed", "warning"})


@dataclass(frozen=True)
class ExecutionRobotConfig:
    loop_run_id: str
    debate_id: str | None = None
    max_total_tokens_per_loop: int = 500_000
    max_cost_usd_per_loop: float | None = None
    max_output_tokens: int = 2_048


class ExecutionRobot:
    """AI selector that cannot mutate orders or bypass deterministic execution gates."""

    def __init__(
        self,
        store: SQLiteStore,
        providers: dict[str, OpenAICompatibleProvider],
        ai_store: AISitesConfigStore,
        client: OpenAICompatibleClient | None = None,
    ):
        self.store = store
        self.providers = providers
        self.ai_store = ai_store
        self.client = client or OpenAICompatibleClient()
        self.budget_guard = AIBudgetGuard(store)
        self.invocation_recorder = AIInvocationRecorder(store, ai_store)

    def run(
        self,
        *,
        decisions: list[dict[str, Any]],
        portfolio_risk: dict[str, Any] | None,
        config: ExecutionRobotConfig,
    ) -> dict[str, Any]:
        binding = self.ai_store.task_binding(AI_TASK_ID_EXECUTION_ROBOT)
        if not binding.enabled:
            return self._result("skipped", [], [], ["执行机器人配置关闭"])

        candidates = [_decision_input(item) for item in decisions if _eligible_input(item)]
        if not candidates:
            return self._result("empty", [], [], ["没有可供执行机器人复核的方向性候选"])

        risk_status = _risk_status(portfolio_risk)
        if risk_status not in SAFE_RISK_STATUSES:
            return self._result("blocked", [], [], ["Portfolio Risk 门禁未通过"])

        prompt = self.ai_store.prompt(AI_TASK_ID_EXECUTION_ROBOT)
        payload = {
            "task": AI_TASK_ID_EXECUTION_ROBOT,
            "loop_run_id": config.loop_run_id,
            "portfolio_risk": _risk_input(portfolio_risk),
            "decisions": candidates,
            "required_output": {
                "decision_reviews": [
                    {
                        "decision_id": "必须来自输入",
                        "execute": False,
                        "priority": 1,
                        "reason": "简短、可审计理由",
                        "risk_flags": [],
                    }
                ],
                "summary": "最终执行筛选摘要",
            },
        }
        payload_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        system_prompt = prompt.system_prompt
        user_prompt = render_prompt_template(
            prompt.user_prompt_template,
            payload_json=payload_json,
            target_type="execution_decisions",
            target_id=config.loop_run_id,
        )
        prompt_version = self.invocation_recorder.register_prompt(
            task_id=AI_TASK_ID_EXECUTION_ROBOT,
            role_id="execution_robot_initial",
            system_prompt=system_prompt,
            user_prompt_template=prompt.user_prompt_template,
        )
        reflection_system_prompt = (
            f"{system_prompt}\n"
            "你正在执行第二阶段反思终审。必须主动寻找初审中的过度自信、证据冲突、"
            "遗漏风险和不应执行的理由。终审结论必须重新覆盖每个输入 decision_id；"
            "任何不确定项都应 execute=false。"
        )
        reflection_prompt_version = self.invocation_recorder.register_prompt(
            task_id=AI_TASK_ID_EXECUTION_ROBOT,
            role_id="execution_robot_reflection",
            system_prompt=reflection_system_prompt,
            user_prompt_template=prompt.user_prompt_template,
        )
        errors: list[str] = []

        for attempt_index, provider in enumerate(self._providers(binding), start=1):
            status = provider_status(provider, binding.protocol)
            if not status.configured:
                errors.append(f"{provider.name}: 配置不完整")
                self._record(
                    config=config,
                    message_id=stable_id("execution-robot-initial", config.loop_run_id),
                    role_id="execution_robot_initial",
                    prompt_version=prompt_version,
                    input_hash=prompt_input_hash(system_prompt, user_prompt),
                    provider=provider,
                    protocol=binding.protocol,
                    status="skipped",
                    usage={},
                    duration_ms=0,
                    error=errors[-1],
                    attempt_index=attempt_index,
                )
                continue

            initial_completion, initial_error = self._complete_stage(
                config=config,
                provider=provider,
                protocol=binding.protocol,
                reasoning_effort=binding.reasoning_effort,
                role_id="execution_robot_initial",
                message_id=stable_id("execution-robot-initial", config.loop_run_id),
                prompt_version=prompt_version,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                attempt_index=attempt_index * 10 + 1,
            )
            if initial_completion is None:
                errors.append(f"{provider.name} 初审失败: {initial_error}")
                continue

            try:
                initial_reviews, _, _ = _normalize_reviews(initial_completion.content, candidates)
            except (ValueError, json.JSONDecodeError) as exc:
                errors.append(f"{provider.name} 初审解析失败: {_safe_error(exc)}")
                continue

            reflection_payload = {
                **payload,
                "stage": "reflection_final_review",
                "initial_decision_reviews": initial_reviews,
                "reflection_requirements": [
                    "逐项质疑初审结论，不得直接照抄",
                    "检查证据冲突、风险遗漏、方向与价格关系",
                    "不确定或信息不足时 execute=false",
                    "重新输出全部输入 decision_id 的最终结论",
                ],
            }
            reflection_user_prompt = render_prompt_template(
                prompt.user_prompt_template,
                payload_json=json.dumps(reflection_payload, ensure_ascii=False, separators=(",", ":")),
                target_type="execution_decisions_reflection",
                target_id=config.loop_run_id,
            )
            reflection_completion, reflection_error = self._complete_stage(
                config=config,
                provider=provider,
                protocol=binding.protocol,
                reasoning_effort=binding.reasoning_effort,
                role_id="execution_robot_reflection",
                message_id=stable_id("execution-robot-reflection", config.loop_run_id),
                prompt_version=reflection_prompt_version,
                system_prompt=reflection_system_prompt,
                user_prompt=reflection_user_prompt,
                attempt_index=attempt_index * 10 + 2,
            )
            if reflection_completion is None:
                errors.append(f"{provider.name} 反思终审失败: {reflection_error}")
                continue

            try:
                reviews, approved_ids, rejected = _normalize_reviews(reflection_completion.content, candidates)
            except (ValueError, json.JSONDecodeError) as exc:
                errors.append(f"{provider.name} 反思终审解析失败: {_safe_error(exc)}")
                continue

            approved = [item for item in decisions if str(item.get("decision_id")) in approved_ids]
            return {
                **self._result("passed", approved, reviews, rejected),
                "initial_decision_reviews": initial_reviews,
                "reflection": {
                    "required": True,
                    "status": "passed",
                    "site_id": provider.name,
                    "model": reflection_completion.model,
                    "reasoning_effort": binding.reasoning_effort,
                },
                "site_id": provider.name,
                "protocol": binding.protocol,
                "model": reflection_completion.model,
                "reasoning_effort": binding.reasoning_effort,
            }

        return self._result("failed", [], [], errors or ["执行机器人无可用供应商"])

    def _complete_stage(
        self,
        *,
        config: ExecutionRobotConfig,
        provider: OpenAICompatibleProvider,
        protocol: str,
        reasoning_effort: str,
        role_id: str,
        message_id: str,
        prompt_version: str,
        system_prompt: str,
        user_prompt: str,
        attempt_index: int,
    ) -> tuple[Any | None, str | None]:
        pricing = self.ai_store.site_pricing(provider.name, model=provider.model_for(protocol))
        permit = self.budget_guard.acquire(
            loop_run_id=config.loop_run_id,
            site_id=provider.name,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            pricing=pricing,
            policy=AIBudgetPolicy(
                max_total_tokens_per_loop=config.max_total_tokens_per_loop,
                max_cost_usd_per_loop=config.max_cost_usd_per_loop,
                max_output_tokens_per_call=config.max_output_tokens,
            ),
        )
        if not permit.allowed:
            error = permit.reason or "AI budget blocked"
            self._record(
                config=config,
                message_id=message_id,
                role_id=role_id,
                prompt_version=prompt_version,
                input_hash=prompt_input_hash(system_prompt, user_prompt),
                provider=provider,
                protocol=protocol,
                status="budget_blocked",
                usage={},
                duration_ms=0,
                error=error,
                attempt_index=attempt_index,
            )
            return None, error

        timer = perf_counter()
        try:
            completion = self.client.complete(
                provider=provider,
                protocol=protocol,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                require_json=True,
                max_output_tokens=permit.max_output_tokens,
                reasoning_effort=reasoning_effort,
            )
            duration_ms = int((perf_counter() - timer) * 1000)
            invocation = self._record(
                config=config,
                message_id=message_id,
                role_id=role_id,
                prompt_version=prompt_version,
                input_hash=prompt_input_hash(system_prompt, user_prompt),
                provider=provider,
                protocol=protocol,
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
            return completion, None
        except (OpenAICompatibleError, ValueError, json.JSONDecodeError) as exc:
            duration_ms = int((perf_counter() - timer) * 1000)
            error = _safe_error(exc)
            invocation = self._record(
                config=config,
                message_id=message_id,
                role_id=role_id,
                prompt_version=prompt_version,
                input_hash=prompt_input_hash(system_prompt, user_prompt),
                provider=provider,
                protocol=protocol,
                status="failed",
                usage={},
                duration_ms=duration_ms,
                error=error,
                attempt_index=attempt_index,
            )
            self.budget_guard.release(
                permit,
                total_tokens=int(invocation.get("total_tokens") or 0),
                estimated_cost_usd=invocation.get("estimated_cost_usd"),
            )
            return None, error

    def _providers(self, binding: Any) -> list[OpenAICompatibleProvider]:
        ordered: list[OpenAICompatibleProvider] = []
        for site_id in binding.provider_order():
            provider = self.providers.get(site_id)
            if provider is None:
                continue
            ordered.append(
                replace(
                    provider,
                    chat_model=binding.model if binding.protocol == "chat" else provider.chat_model,
                    responses_model=binding.model if binding.protocol == "responses" else provider.responses_model,
                )
            )
        return ordered

    def _record(self, **kwargs: Any) -> dict[str, Any]:
        config: ExecutionRobotConfig = kwargs.pop("config")
        provider: OpenAICompatibleProvider = kwargs.pop("provider")
        role_id = str(kwargs.pop("role_id"))
        return self.invocation_recorder.record(
            loop_run_id=config.loop_run_id,
            debate_id=config.debate_id or config.loop_run_id,
            task_id=AI_TASK_ID_EXECUTION_ROBOT,
            role_id=role_id,
            site_id=provider.name,
            model=provider.model_for(kwargs.get("protocol")),
            experiment_id=None,
            variant_id=None,
            **kwargs,
        )

    @staticmethod
    def _result(
        status: str,
        approved_decisions: list[dict[str, Any]],
        reviews: list[dict[str, Any]],
        reasons: list[str],
    ) -> dict[str, Any]:
        return {
            "status": status,
            "approved_decision_ids": [str(item.get("decision_id")) for item in approved_decisions],
            "approved_decisions": approved_decisions,
            "decision_reviews": reviews,
            "summary": {
                "approved_count": len(approved_decisions),
                "review_count": len(reviews),
                "reasons": reasons,
            },
            "policy": {
                "selection_only": True,
                "reflection_required": True,
                "ai_controls_order_size": False,
                "ai_can_change_direction": False,
                "deterministic_risk_gates_required": True,
                "mainnet_allowed": False,
            },
        }


def _eligible_input(decision: dict[str, Any]) -> bool:
    return bool(
        decision.get("decision_id")
        and str(decision.get("action") or "").upper() in DIRECTIONAL_ACTIONS
        and str(decision.get("status") or "").lower() == "candidate"
    )


def _decision_input(decision: dict[str, Any]) -> dict[str, Any]:
    return {
        key: decision.get(key)
        for key in (
            "decision_id",
            "candidate_id",
            "product_id",
            "instrument_id",
            "symbol",
            "provider",
            "market_type",
            "action",
            "confidence",
            "entry_reference",
            "target_price",
            "invalidation_price",
            "rationale",
            "risk_warnings",
            "evidence_refs",
            "invalidation_conditions",
            "human_review_status",
        )
    }


def _risk_status(portfolio_risk: dict[str, Any] | None) -> str:
    value = portfolio_risk or {}
    return str((value.get("risk_gate") or {}).get("status") or (value.get("summary") or {}).get("risk_status") or "").lower()


def _risk_input(portfolio_risk: dict[str, Any] | None) -> dict[str, Any]:
    value = portfolio_risk or {}
    return {
        "summary": value.get("summary") or {},
        "risk_gate": value.get("risk_gate") or {},
        "warnings": value.get("warnings") or [],
    }


def _normalize_reviews(content: str, candidates: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], set[str], list[str]]:
    payload = _parse_json_object(content)
    raw_reviews = payload.get("decision_reviews")
    if not isinstance(raw_reviews, list):
        raise ValueError("执行机器人缺少 decision_reviews")
    candidate_ids = {str(item["decision_id"]) for item in candidates}
    reviews: list[dict[str, Any]] = []
    seen: set[str] = set()
    approved: set[str] = set()
    rejected: list[str] = []
    for raw in raw_reviews:
        if not isinstance(raw, dict):
            continue
        decision_id = str(raw.get("decision_id") or "")
        if decision_id not in candidate_ids or decision_id in seen:
            continue
        seen.add(decision_id)
        execute = raw.get("execute") is True
        review = {
            "decision_id": decision_id,
            "execute": execute,
            "priority": max(1, int(raw.get("priority") or len(reviews) + 1)),
            "reason": str(raw.get("reason") or "未提供理由")[:500],
            "risk_flags": [str(item)[:200] for item in raw.get("risk_flags", []) if str(item).strip()][:20],
        }
        reviews.append(review)
        if execute:
            approved.add(decision_id)
        else:
            rejected.append(f"{decision_id}: {review['reason']}")
    for decision_id in sorted(candidate_ids - seen):
        reviews.append(
            {
                "decision_id": decision_id,
                "execute": False,
                "priority": len(reviews) + 1,
                "reason": "模型未明确复核，按 fail-closed 拒绝",
                "risk_flags": ["missing_review"],
            }
        )
        rejected.append(f"{decision_id}: 模型未明确复核")
    return reviews, approved, rejected


def _parse_json_object(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text
        text = text.rsplit("```", 1)[0]
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("执行机器人返回内容不是 JSON 对象")
    payload = json.loads(text[start : end + 1])
    if not isinstance(payload, dict):
        raise ValueError("执行机器人返回 JSON 根节点必须是对象")
    return payload


def _safe_error(exc: Exception) -> str:
    return f"{type(exc).__name__}: {str(exc)[:300]}"
