from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from finbot.council.models import CouncilTemplate


WORKFLOW_DEPTH_POLICIES: dict[str, dict[str, Any]] = {
    "quick": {
        "label": "快速",
        "max_total_tokens": 30_000,
        "max_cost_usd": 0.15,
        "max_duration_seconds": 120,
        "max_rounds": 2,
    },
    "standard": {
        "label": "标准",
        "max_total_tokens": 120_000,
        "max_cost_usd": 0.50,
        "max_duration_seconds": 600,
        "max_rounds": 6,
    },
    "deep": {
        "label": "深度",
        "max_total_tokens": 400_000,
        "max_cost_usd": 2.00,
        "max_duration_seconds": 1_800,
        "max_rounds": 10,
    },
}


@dataclass(frozen=True)
class DirectorSelection:
    template_id: str
    depth: str
    reason: str


class ResearchDirector:
    def plan(
        self,
        request: dict[str, Any],
        templates: tuple[CouncilTemplate, ...],
    ) -> dict[str, Any]:
        available = {template.template_id: template for template in templates if template.enabled}
        if not available:
            raise ValueError("没有可用的工作流模板")
        selection = self._select(request, available)
        template = available[selection.template_id]
        policy = dict(WORKFLOW_DEPTH_POLICIES[selection.depth])
        requested_rounds = _optional_int(request.get("rounds"))
        default_rounds = template.round_policy.default_rounds
        rounds = requested_rounds if requested_rounds is not None else default_rounds
        rounds = max(
            template.round_policy.min_rounds,
            min(rounds, template.round_policy.max_rounds, int(policy["max_rounds"])),
        )
        facts, assumptions, gaps = self._ledgers(request)
        steps = [
            {
                "step_index": index,
                "node_id": node.node_id,
                "node_type": node.node_type,
                "operation": node.operation,
                "role_id": node.role_id,
                "phase_ids": list(node.phase_ids),
                "retry": node.retry_policy.to_dict(),
            }
            for index, node in enumerate(_topological_nodes(template), start=1)
        ]
        normalized_request = _safe_request(request)
        plan_id = _stable_id(
            "workflow-plan",
            template.template_id,
            selection.depth,
            normalized_request,
        )
        return {
            "plan_id": plan_id,
            "director_version": 1,
            "template_id": template.template_id,
            "template_name": template.display_name,
            "template_description": template.description,
            "depth": selection.depth,
            "cost_tier": selection.depth,
            "selection_reason": selection.reason,
            "trigger_type": str(request.get("trigger_type") or "manual"),
            "objective": _objective(request, template),
            "rounds": rounds,
            "budget_policy": policy,
            "facts": facts,
            "assumptions": assumptions,
            "gaps": gaps,
            "steps": steps,
            "revisions": [],
            "policy": {
                "deterministic_template_selection": True,
                "llm_may_expand_budget": False,
                "llm_may_publish_workflow": False,
                "trading_execution_allowed": False,
                "hidden_reasoning_persisted": False,
            },
        }

    def replan(
        self,
        plan: dict[str, Any],
        *,
        reason: str,
        failed_node_id: str | None = None,
        error_kind: str = "node_failure",
    ) -> dict[str, Any]:
        revisions = [dict(item) for item in plan.get("revisions", []) if isinstance(item, dict)]
        current_depth = str(plan.get("depth") or "standard")
        if error_kind == "evidence_gap" and current_depth != "deep":
            action = "escalate_to_deep_review"
            target_template_id = "deep_investment_committee"
        elif error_kind in {"budget_exceeded", "time_exceeded"}:
            action = "terminate_with_partial_result"
            target_template_id = plan.get("template_id")
        elif error_kind == "provider_failure":
            action = "use_configured_provider_fallback_or_stop"
            target_template_id = plan.get("template_id")
        else:
            action = "retry_from_latest_checkpoint_then_stop"
            target_template_id = plan.get("template_id")
        revision = {
            "revision": len(revisions) + 1,
            "reason": " ".join(str(reason).split())[:500],
            "error_kind": error_kind,
            "failed_node_id": failed_node_id,
            "action": action,
            "target_template_id": target_template_id,
        }
        return {**plan, "revisions": [*revisions, revision], "latest_revision": revision}

    def _select(
        self,
        request: dict[str, Any],
        available: dict[str, CouncilTemplate],
    ) -> DirectorSelection:
        explicit_template = str(request.get("template_id") or "").strip()
        requested_depth = str(request.get("depth") or "").strip().lower()
        query = str(request.get("query") or "").lower()
        trigger = str(request.get("trigger_type") or "manual").lower()
        if requested_depth not in WORKFLOW_DEPTH_POLICIES:
            requested_depth = ""
        if explicit_template:
            if explicit_template not in available:
                raise ValueError(f"指定的工作流模板不可用：{explicit_template}")
            template = available[explicit_template]
            depth = requested_depth or template.cost_tier
            return DirectorSelection(explicit_template, depth, "用户显式选择模板")
        if _contains_any(f"{trigger} {query}", ("position", "持仓", "复盘", "退出", "止损", "原建议")):
            return DirectorSelection("position_review", requested_depth or "standard", "识别为既有建议或持仓复核")
        if _contains_any(f"{trigger} {query}", ("event", "news", "公告", "事件", "宏观", "冲击", "监管")):
            return DirectorSelection("event_impact_analysis", requested_depth or "standard", "识别为事件冲击问题")
        if requested_depth == "deep" or _contains_any(query, ("深度", "投委会", "全面", "情景分析", "组合风险")):
            return DirectorSelection("deep_investment_committee", "deep", "问题要求深度审查或投委会门禁")
        if requested_depth == "quick" or _contains_any(trigger, ("scheduled", "monitor", "watchlist", "scan")):
            return DirectorSelection("quick_market_scan", "quick", "监控或快速扫描触发")
        return DirectorSelection("standard_product_research", requested_depth or "standard", "采用默认标准产品研究流程")

    @staticmethod
    def _ledgers(request: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str], list[str]]:
        facts: list[dict[str, Any]] = []
        for key in ("query", "product_id", "symbol", "product_type", "market_type", "evidence_status"):
            value = request.get(key)
            if value not in (None, "", []):
                facts.append({"field": key, "value": value, "source": "run_request"})
        assumptions = [
            "所有 AI 输出仅作为研究与人工复核材料。",
            "不存在于输入或已存证据中的事实不得被视为已确认。",
        ]
        gaps: list[str] = []
        if not request.get("query"):
            gaps.append("缺少明确研究问题，将按触发类型和产品上下文执行。")
        if not request.get("evidence_status"):
            gaps.append("输入未声明证据状态，需先执行证据质量检查。")
        if not any(request.get(key) for key in ("product_id", "symbol", "product_type")):
            gaps.append("未锁定具体产品，结果只能形成市场级或主题级结论。")
        return facts, assumptions, gaps


def _topological_nodes(template: CouncilTemplate) -> list[Any]:
    nodes = {node.node_id: node for node in template.workflow.nodes}
    indegrees = {node_id: 0 for node_id in nodes}
    adjacency = {node_id: [] for node_id in nodes}
    for edge in template.workflow.edges:
        if edge.loop:
            continue
        adjacency[edge.source_node_id].append(edge.target_node_id)
        indegrees[edge.target_node_id] += 1
    queue = [node.node_id for node in template.workflow.nodes if indegrees[node.node_id] == 0]
    ordered = []
    while queue:
        current = queue.pop(0)
        ordered.append(nodes[current])
        for target in adjacency[current]:
            indegrees[target] -= 1
            if indegrees[target] == 0:
                queue.append(target)
    return ordered


def _objective(request: dict[str, Any], template: CouncilTemplate) -> str:
    query = " ".join(str(request.get("query") or "").split()).strip()
    if query:
        return query[:500]
    return f"使用{template.display_name}检查输入产品或市场上下文，并形成可审计的人工参考结论。"


def _safe_request(request: dict[str, Any]) -> dict[str, Any]:
    return {
        str(key): _safe_value(value, str(key))
        for key, value in request.items()
        if not _sensitive_key(str(key))
    }


def _safe_value(value: Any, key: str) -> Any:
    if _sensitive_key(key):
        return "***"
    if isinstance(value, dict):
        return {str(item_key): _safe_value(item, str(item_key)) for item_key, item in list(value.items())[:100]}
    if isinstance(value, (list, tuple)):
        return [_safe_value(item, key) for item in list(value)[:100]]
    if isinstance(value, str):
        return value[:2000]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return str(value)[:2000]


def _sensitive_key(key: str) -> bool:
    lowered = key.lower()
    return any(marker in lowered for marker in ("api_key", "secret", "password", "authorization", "access_token"))


def _stable_id(*parts: Any) -> str:
    encoded = json.dumps(parts, ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()[:32]


def _contains_any(value: str, terms: tuple[str, ...]) -> bool:
    return any(term in value for term in terms)


def _optional_int(value: Any) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None
