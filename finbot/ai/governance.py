from __future__ import annotations

import hashlib
import json
import threading
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from statistics import mean
from typing import Any, Mapping
from uuid import uuid4

from finbot.config.ai_sites import AISitesConfigStore
from finbot.storage.sqlite_store import SQLiteStore


@dataclass(frozen=True)
class AIBudgetPolicy:
    max_total_tokens_per_loop: int = 500_000
    max_cost_usd_per_loop: float | None = None
    max_output_tokens_per_call: int = 4_096


@dataclass(frozen=True)
class AIBudgetPermit:
    permit_id: str
    allowed: bool
    loop_run_id: str
    site_id: str
    max_output_tokens: int
    reserved_tokens: int
    reserved_cost_usd: float
    cost_status: str
    reason: str | None = None


@dataclass(frozen=True)
class AIGovernanceConfig:
    max_total_tokens_per_loop: int = 500_000
    max_cost_usd_per_loop: float | None = None
    minimum_claim_evidence_coverage: float = 0.8


class AIBudgetGuard:
    """Conservative loop budget guard shared by concurrent Council turns."""

    def __init__(self, store: SQLiteStore):
        self.store = store
        self._lock = threading.Lock()
        self._states: dict[str, dict[str, Any]] = {}
        self._permits: dict[str, AIBudgetPermit] = {}

    def acquire(
        self,
        *,
        loop_run_id: str,
        site_id: str,
        system_prompt: str,
        user_prompt: str,
        pricing: Mapping[str, Any],
        policy: AIBudgetPolicy,
    ) -> AIBudgetPermit:
        input_token_upper_bound = max(
            1,
            len(system_prompt.encode("utf-8")) + len(user_prompt.encode("utf-8")),
        )
        desired_output = max(64, int(policy.max_output_tokens_per_call))
        with self._lock:
            state = self._state(loop_run_id)
            available_tokens = max(0, int(policy.max_total_tokens_per_loop) - state["actual_tokens"] - state["reserved_tokens"])
            output_cap = min(desired_output, max(0, available_tokens - input_token_upper_bound))
            if output_cap < 64:
                return AIBudgetPermit(
                    permit_id=uuid4().hex,
                    allowed=False,
                    loop_run_id=loop_run_id,
                    site_id=site_id,
                    max_output_tokens=0,
                    reserved_tokens=0,
                    reserved_cost_usd=0.0,
                    cost_status="unknown",
                    reason=(
                        f"AI token budget exhausted: available={available_tokens}, "
                        f"conservative_input_bound={input_token_upper_bound}"
                    ),
                )

            input_rate = pricing.get("input_cost_per_million_tokens")
            output_rate = pricing.get("output_cost_per_million_tokens")
            cost_known = input_rate is not None and output_rate is not None
            reserved_cost = (
                input_token_upper_bound * float(input_rate) / 1_000_000
                + output_cap * float(output_rate) / 1_000_000
                if cost_known else 0.0
            )
            if (
                policy.max_cost_usd_per_loop is not None
                and cost_known
                and state["actual_cost_usd"] + state["reserved_cost_usd"] + reserved_cost
                > policy.max_cost_usd_per_loop
            ):
                return AIBudgetPermit(
                    permit_id=uuid4().hex,
                    allowed=False,
                    loop_run_id=loop_run_id,
                    site_id=site_id,
                    max_output_tokens=0,
                    reserved_tokens=0,
                    reserved_cost_usd=0.0,
                    cost_status="known",
                    reason="AI USD budget exhausted",
                )

            permit = AIBudgetPermit(
                permit_id=uuid4().hex,
                allowed=True,
                loop_run_id=loop_run_id,
                site_id=site_id,
                max_output_tokens=output_cap,
                reserved_tokens=input_token_upper_bound + output_cap,
                reserved_cost_usd=reserved_cost,
                cost_status="known" if cost_known else "unknown",
            )
            state["reserved_tokens"] += permit.reserved_tokens
            state["reserved_cost_usd"] += permit.reserved_cost_usd
            self._permits[permit.permit_id] = permit
            return permit

    def release(
        self,
        permit: AIBudgetPermit,
        *,
        total_tokens: int = 0,
        estimated_cost_usd: float | None = None,
    ) -> None:
        if not permit.allowed:
            return
        with self._lock:
            active = self._permits.pop(permit.permit_id, None)
            if active is None:
                return
            state = self._state(permit.loop_run_id)
            state["reserved_tokens"] = max(0, state["reserved_tokens"] - active.reserved_tokens)
            state["reserved_cost_usd"] = max(0.0, state["reserved_cost_usd"] - active.reserved_cost_usd)
            state["actual_tokens"] += max(0, int(total_tokens))
            if estimated_cost_usd is not None:
                state["actual_cost_usd"] += max(0.0, float(estimated_cost_usd))

    def snapshot(self, loop_run_id: str, policy: AIBudgetPolicy) -> dict[str, Any]:
        with self._lock:
            state = dict(self._state(loop_run_id))
        token_limit = max(1, int(policy.max_total_tokens_per_loop))
        return {
            **state,
            "max_total_tokens_per_loop": token_limit,
            "token_utilization": round(state["actual_tokens"] / token_limit, 6),
            "max_cost_usd_per_loop": policy.max_cost_usd_per_loop,
        }

    def _state(self, loop_run_id: str) -> dict[str, Any]:
        state = self._states.get(loop_run_id)
        if state is not None:
            return state
        invocations = [dict(row) for row in self.store.list_ai_invocations(loop_run_id=loop_run_id)]
        state = {
            "actual_tokens": sum(int(item.get("total_tokens") or 0) for item in invocations),
            "actual_cost_usd": sum(float(item.get("estimated_cost_usd") or 0.0) for item in invocations),
            "reserved_tokens": 0,
            "reserved_cost_usd": 0.0,
        }
        self._states[loop_run_id] = state
        return state


class AIInvocationRecorder:
    def __init__(self, store: SQLiteStore, ai_store: AISitesConfigStore):
        self.store = store
        self.ai_store = ai_store

    def register_prompt(
        self,
        *,
        task_id: str,
        role_id: str | None,
        system_prompt: str,
        user_prompt_template: str,
    ) -> str:
        prompt_version = prompt_template_version(system_prompt, user_prompt_template)
        self.store.upsert_ai_prompt_version(
            {
                "prompt_version": prompt_version,
                "task_id": task_id,
                "role_id": role_id,
                "system_prompt": system_prompt,
                "user_prompt_template": user_prompt_template,
                "created_at": _now(),
            }
        )
        return prompt_version

    def record(
        self,
        *,
        loop_run_id: str,
        debate_id: str,
        message_id: str,
        task_id: str,
        role_id: str,
        site_id: str | None,
        protocol: str | None,
        model: str | None,
        prompt_version: str,
        input_hash: str,
        experiment_id: str | None,
        variant_id: str | None,
        status: str,
        usage: dict[str, Any] | None,
        duration_ms: int,
        error: str | None,
        attempt_index: int,
    ) -> dict[str, Any]:
        normalized_usage = normalize_usage(usage or {})
        pricing = self.ai_store.site_pricing(site_id, model=model) if site_id else {
            "input_cost_per_million_tokens": None,
            "output_cost_per_million_tokens": None,
        }
        cost, cost_status = estimate_cost(normalized_usage, pricing)
        invocation = {
            "invocation_id": _hash_id(
                "ai-invocation",
                message_id,
                site_id,
                attempt_index,
                status,
            ),
            "loop_run_id": loop_run_id,
            "debate_id": debate_id,
            "message_id": message_id,
            "task_id": task_id,
            "role_id": role_id,
            "site_id": site_id,
            "protocol": protocol,
            "model": model,
            "prompt_version": prompt_version,
            "input_hash": input_hash,
            "experiment_id": experiment_id,
            "variant_id": variant_id,
            "status": status,
            **normalized_usage,
            "estimated_cost_usd": cost,
            "cost_status": cost_status,
            "duration_ms": duration_ms,
            "error": error,
            "created_at": _now(),
        }
        self.store.insert_ai_invocation(invocation)
        return invocation


class ClaimEvidenceAuditor:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def audit(
        self,
        *,
        loop_run_id: str,
        debate_id: str,
        messages: list[dict[str, Any]],
    ) -> dict[str, Any]:
        created_at = _now()
        audits: list[dict[str, Any]] = []
        for message in messages:
            if str(message.get("status") or "") != "completed":
                continue
            content = _json_object(message.get("content") or message.get("content_json"))
            claims = _claims_from_content(content)
            for index, claim in enumerate(claims, start=1):
                claim_id = str(claim.get("claim_id") or f"claim-{index}")
                evidence_refs = _evidence_refs(claim.get("evidence_refs"))
                audits.append(
                    {
                        "audit_id": _hash_id("claim-audit", message.get("message_id"), claim_id, index),
                        "loop_run_id": loop_run_id,
                        "debate_id": debate_id,
                        "message_id": str(message.get("message_id") or ""),
                        "role_id": message.get("agent_role"),
                        "phase_id": message.get("phase_id"),
                        "claim_id": claim_id,
                        "claim_source": claim["claim_source"],
                        "claim_text": claim.get("text"),
                        "covered": bool(evidence_refs),
                        "derived": bool(claim.get("derived")),
                        "evidence_refs": evidence_refs,
                        "created_at": created_at,
                    }
                )
        self.store.replace_claim_evidence_audits(debate_id, audits)
        covered = sum(item["covered"] for item in audits)
        explicit = [item for item in audits if not item["derived"]]
        return {
            "claim_count": len(audits),
            "covered_claim_count": covered,
            "coverage_ratio": round(covered / len(audits), 6) if audits else None,
            "explicit_claim_count": len(explicit),
            "derived_claim_count": len(audits) - len(explicit),
            "audits": audits,
        }


class AIGovernanceReporter:
    def __init__(self, store: SQLiteStore, ai_store: AISitesConfigStore | None = None):
        self.store = store
        self.ai_store = ai_store

    def build(
        self,
        *,
        loop_run_id: str,
        debate_id: str | None,
        config: AIGovernanceConfig | None = None,
    ) -> dict[str, Any]:
        policy = config or AIGovernanceConfig()
        created_at = _now()
        invocations, repriced_invocation_count = self._invocations_with_current_pricing(loop_run_id)
        messages = []
        if debate_id:
            for row in self.store.list_ai_debate_messages(debate_id=debate_id):
                item = dict(row)
                item["content"] = _json_object(item.get("content_json"))
                messages.append(item)
        claim_audit = (
            ClaimEvidenceAuditor(self.store).audit(
                loop_run_id=loop_run_id,
                debate_id=debate_id,
                messages=messages,
            )
            if debate_id else {
                "claim_count": 0,
                "covered_claim_count": 0,
                "coverage_ratio": None,
                "explicit_claim_count": 0,
                "derived_claim_count": 0,
                "audits": [],
            }
        )
        successful = [item for item in invocations if item.get("status") == "completed"]
        total_tokens = sum(int(item.get("total_tokens") or 0) for item in invocations)
        known_cost = sum(float(item.get("estimated_cost_usd") or 0.0) for item in successful)
        unknown_cost_count = sum(item.get("cost_status") != "known" for item in successful)
        warnings = []
        if messages and not invocations:
            warnings.append(
                {
                    "code": "invocation_telemetry_unavailable_for_legacy_run",
                    "message_count": len(messages),
                }
            )
        if total_tokens > policy.max_total_tokens_per_loop:
            warnings.append(
                {
                    "code": "token_budget_exceeded",
                    "actual": total_tokens,
                    "threshold": policy.max_total_tokens_per_loop,
                }
            )
        if policy.max_cost_usd_per_loop is not None:
            if unknown_cost_count:
                warnings.append(
                    {
                        "code": "cost_budget_not_enforceable_missing_site_rates",
                        "unknown_invocation_count": unknown_cost_count,
                    }
                )
            elif known_cost > policy.max_cost_usd_per_loop:
                warnings.append(
                    {
                        "code": "cost_budget_exceeded",
                        "actual": round(known_cost, 8),
                        "threshold": policy.max_cost_usd_per_loop,
                    }
                )
        coverage = claim_audit["coverage_ratio"]
        if claim_audit["claim_count"] and not claim_audit["explicit_claim_count"]:
            warnings.append(
                {
                    "code": "claim_coverage_derived_from_legacy_schema",
                    "derived_claim_count": claim_audit["derived_claim_count"],
                }
            )
        if coverage is not None and coverage < policy.minimum_claim_evidence_coverage:
            warnings.append(
                {
                    "code": "claim_evidence_coverage_below_threshold",
                    "actual": coverage,
                    "threshold": policy.minimum_claim_evidence_coverage,
                }
            )
        summary = {
            "governance_status": "warning" if warnings else "passed",
            "invocation_count": len(invocations),
            "successful_invocation_count": len(successful),
            "failed_invocation_count": len(invocations) - len(successful),
            "input_tokens": sum(int(item.get("input_tokens") or 0) for item in invocations),
            "output_tokens": sum(int(item.get("output_tokens") or 0) for item in invocations),
            "total_tokens": total_tokens,
            "estimated_cost_usd": (
                None if not successful or unknown_cost_count else round(known_cost, 8)
            ),
            "known_cost_subtotal_usd": round(known_cost, 8),
            "cost_status": "not_available" if not successful else "unknown" if unknown_cost_count else "known",
            "unknown_cost_invocation_count": unknown_cost_count,
            "repriced_invocation_count": repriced_invocation_count,
            "claim_count": claim_audit["claim_count"],
            "claim_evidence_coverage": coverage,
            "explicit_claim_count": claim_audit["explicit_claim_count"],
            "derived_claim_count": claim_audit["derived_claim_count"],
            "warning_count": len(warnings),
        }
        report = {
            "status": "passed",
            "governance_report_id": _hash_id("ai-governance", loop_run_id, created_at),
            "loop_run_id": loop_run_id,
            "debate_id": debate_id,
            "created_at": created_at,
            "config": asdict(policy),
            "summary": summary,
            "budget": {
                "max_total_tokens_per_loop": policy.max_total_tokens_per_loop,
                "token_utilization": round(total_tokens / max(1, policy.max_total_tokens_per_loop), 6),
                "max_cost_usd_per_loop": policy.max_cost_usd_per_loop,
                "cost_budget_enforceable": bool(successful) and unknown_cost_count == 0,
            },
            "groups": {
                "provider_model": _invocation_groups(invocations, ("site_id", "model")),
                "prompt_version": _invocation_groups(invocations, ("prompt_version",)),
                "experiment_variant": _invocation_groups(invocations, ("experiment_id", "variant_id")),
            },
            "claim_evidence": {key: value for key, value in claim_audit.items() if key != "audits"},
            "warnings": warnings,
            "policy": {
                "execution_allowed": False,
                "order_api_allowed": False,
                "private_exchange_api_allowed": False,
                "advisory_only": True,
            },
        }
        self.store.insert_ai_governance_report(report)
        return report

    def _invocations_with_current_pricing(self, loop_run_id: str) -> tuple[list[dict[str, Any]], int]:
        invocations = [dict(row) for row in self.store.list_ai_invocations(loop_run_id=loop_run_id)]
        if self.ai_store is None:
            return invocations, 0
        repriced_count = 0
        for invocation in invocations:
            if invocation.get("cost_status") == "known":
                continue
            site_id = str(invocation.get("site_id") or "").strip()
            model = str(invocation.get("model") or "").strip() or None
            if not site_id:
                continue
            pricing = self.ai_store.site_pricing(site_id, model=model)
            cost, cost_status = estimate_cost(normalize_usage(invocation), pricing)
            if cost_status != "known":
                continue
            invocation["estimated_cost_usd"] = cost
            invocation["cost_status"] = "known"
            invocation["cost_source"] = "current_config_reprice"
            repriced_count += 1
        return invocations, repriced_count


def prompt_template_version(system_prompt: str, user_prompt_template: str) -> str:
    digest = hashlib.sha256(
        f"prompt-template-v1\n{system_prompt}\n---user-template---\n{user_prompt_template}".encode("utf-8")
    ).hexdigest()
    return f"ptv1:{digest[:24]}"


def prompt_input_hash(system_prompt: str, user_prompt: str) -> str:
    return hashlib.sha256(
        f"prompt-input-v1\n{system_prompt}\n---user---\n{user_prompt}".encode("utf-8")
    ).hexdigest()


def normalize_usage(usage: dict[str, Any]) -> dict[str, int | None]:
    input_tokens = _optional_int(usage.get("input_tokens"))
    if input_tokens is None:
        input_tokens = _optional_int(usage.get("prompt_tokens"))
    output_tokens = _optional_int(usage.get("output_tokens"))
    if output_tokens is None:
        output_tokens = _optional_int(usage.get("completion_tokens"))
    total_tokens = _optional_int(usage.get("total_tokens"))
    if total_tokens is None and (input_tokens is not None or output_tokens is not None):
        total_tokens = int(input_tokens or 0) + int(output_tokens or 0)
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
    }


def estimate_cost(
    usage: dict[str, int | None],
    pricing: Mapping[str, Any],
) -> tuple[float | None, str]:
    input_rate = pricing.get("input_cost_per_million_tokens")
    output_rate = pricing.get("output_cost_per_million_tokens")
    input_tokens = usage.get("input_tokens")
    output_tokens = usage.get("output_tokens")
    if input_rate is None or output_rate is None or input_tokens is None or output_tokens is None:
        return None, "unknown"
    cost = (
        int(input_tokens) * float(input_rate)
        + int(output_tokens) * float(output_rate)
    ) / 1_000_000
    return round(cost, 10), "known"


def _claims_from_content(content: dict[str, Any]) -> list[dict[str, Any]]:
    claims: list[dict[str, Any]] = []
    for raw in content.get("claims") or []:
        if isinstance(raw, dict):
            claims.append({**raw, "claim_source": "explicit_top_level", "derived": False})
    assessments = content.get("candidate_assessments") or []
    for assessment_index, assessment in enumerate(assessments, start=1):
        if not isinstance(assessment, dict):
            continue
        explicit = [item for item in assessment.get("claims") or [] if isinstance(item, dict)]
        for claim in explicit:
            claims.append({**claim, "claim_source": "explicit_assessment", "derived": False})
        if explicit:
            continue
        inherited_refs = _evidence_refs(assessment.get("evidence_refs"))
        for field in ("arguments", "counter_arguments", "risk_flags"):
            for item_index, text in enumerate(_string_list(assessment.get(field)), start=1):
                claims.append(
                    {
                        "claim_id": f"assessment-{assessment_index}-{field}-{item_index}",
                        "text": text,
                        "evidence_refs": inherited_refs,
                        "claim_source": f"derived_{field}",
                        "derived": True,
                    }
                )
    decisions = content.get("decisions") or []
    for decision_index, decision in enumerate(decisions, start=1):
        if not isinstance(decision, dict):
            continue
        inherited_refs = _evidence_refs(decision.get("evidence_refs"))
        for item_index, text in enumerate(_string_list(decision.get("rationale")), start=1):
            claims.append(
                {
                    "claim_id": f"decision-{decision_index}-rationale-{item_index}",
                    "text": text,
                    "evidence_refs": inherited_refs,
                    "claim_source": "derived_decision_rationale",
                    "derived": True,
                }
            )
    return claims


def _invocation_groups(invocations: list[dict[str, Any]], keys: tuple[str, ...]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, ...], list[dict[str, Any]]] = {}
    for invocation in invocations:
        key = tuple(str(invocation.get(name) or "none") for name in keys)
        groups.setdefault(key, []).append(invocation)
    result = []
    for key, values in groups.items():
        successful = [item for item in values if item.get("status") == "completed"]
        durations = [int(item["duration_ms"]) for item in values if item.get("duration_ms") is not None]
        unknown_cost = sum(item.get("cost_status") != "known" for item in successful)
        result.append(
            {
                **dict(zip(keys, key, strict=True)),
                "invocation_count": len(values),
                "success_count": len(successful),
                "total_tokens": sum(int(item.get("total_tokens") or 0) for item in values),
                "estimated_cost_usd": (
                    None if unknown_cost
                    else round(sum(float(item.get("estimated_cost_usd") or 0.0) for item in successful), 8)
                ),
                "average_duration_ms": round(mean(durations), 2) if durations else None,
            }
        )
    return sorted(result, key=lambda item: (-int(item["invocation_count"]), *(str(item[name]) for name in keys)))


def _evidence_refs(value: Any) -> list[str]:
    return list(dict.fromkeys(item for item in _string_list(value) if item and item.lower() not in {"none", "n/a", "unknown"}))


def _string_list(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if not isinstance(value, (list, tuple)):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not value:
        return {}
    try:
        parsed = json.loads(str(value))
    except (TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _optional_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return None


def _hash_id(*parts: Any) -> str:
    payload = ":".join(str(part) for part in parts)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
