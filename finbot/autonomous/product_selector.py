from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ProductSelectionConfig:
    min_confidence: float = 0.0
    limit: int = 10


class ProductRecommendationSelector:
    def select(
        self,
        operator_report: dict[str, Any] | None,
        config: ProductSelectionConfig | None = None,
        ai_decisions: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        config = config or ProductSelectionConfig()
        report = operator_report or {}
        recommendations = self._recommendations_from_ai_decisions(ai_decisions or [], config)
        source = "ai_trade_decisions" if recommendations else "operator_workbench"
        if not recommendations:
            recommendations = [
                recommendation
                for recommendation in (
                    self._recommendation_from_item(item)
                    for item in report.get("items", [])
                    if isinstance(item, dict)
                )
                if recommendation is not None and recommendation["confidence"] >= config.min_confidence
            ]
        recommendations.sort(key=lambda item: (-item["score"], str(item.get("provider") or ""), str(item.get("symbol") or "")))
        selected = recommendations[: max(1, config.limit)]
        return {
            "status": "ok" if selected else "empty",
            "source_report_id": report.get("report_id"),
            "source": source,
            "selected_count": len(selected),
            "candidate_count": len(recommendations),
            "recommended_products": selected,
            "policy": {
                "execution_allowed": False,
                "order_api_allowed": False,
                "human_confirmation_required": True,
                "advisory_only": True,
            },
        }

    def _recommendations_from_ai_decisions(
        self,
        decisions: list[dict[str, Any]],
        config: ProductSelectionConfig,
    ) -> list[dict[str, Any]]:
        recommendations = []
        for decision in decisions:
            if not isinstance(decision, dict):
                continue
            confidence = _float(decision.get("confidence"), 0.0)
            if confidence < config.min_confidence:
                continue
            recommendations.append(
                {
                    "symbol": decision.get("symbol"),
                    "normalized_symbol": decision.get("normalized_symbol"),
                    "provider": decision.get("provider") or "ai-council",
                    "market_type": decision.get("market_type"),
                    "action": str(decision.get("action") or "WATCH").upper(),
                    "status": decision.get("status") or "watch",
                    "confidence": confidence,
                    "score": _float(decision.get("score"), confidence * 100),
                    "horizon": decision.get("horizon"),
                    "entry_reference": decision.get("entry_reference"),
                    "target_price": decision.get("target_price"),
                    "invalidation_price": decision.get("invalidation_price"),
                    "risk_distance_pct": decision.get("risk_distance_pct"),
                    "reward_risk_ratio": decision.get("reward_risk_ratio"),
                    "research_context": decision.get("research_context") or {},
                    "rationale": list(decision.get("rationale") or [])[:6],
                    "risk_warnings": list(decision.get("risk_warnings") or [])[:6],
                    "policy": decision.get("policy") or {
                        "execution_allowed": False,
                        "human_confirmation_required": True,
                        "advisory_only": True,
                    },
                }
            )
        return recommendations

    def _recommendation_from_item(self, item: dict[str, Any]) -> dict[str, Any] | None:
        if item.get("status") != "ok":
            return None
        advice = item.get("advice")
        if not isinstance(advice, dict):
            return None
        action = str(advice.get("action") or "HOLD").upper()
        confidence = _float(advice.get("confidence"), 0.0)
        levels = advice.get("levels") if isinstance(advice.get("levels"), dict) else {}
        research_context = advice.get("research_context") if isinstance(advice.get("research_context"), dict) else {}
        matched_items = research_context.get("matched_items")
        matched_count = len(matched_items) if isinstance(matched_items, list) else 0
        score = round(confidence * 100 + _action_weight(action) + min(10, matched_count * 2), 4)
        return {
            "symbol": advice.get("symbol") or item.get("symbol"),
            "normalized_symbol": advice.get("normalized_symbol"),
            "provider": advice.get("provider") or item.get("provider"),
            "market_type": advice.get("market_type") or item.get("market_type"),
            "action": action,
            "status": "candidate" if action in {"BUY", "SELL"} else "watch",
            "confidence": confidence,
            "score": score,
            "horizon": advice.get("horizon"),
            "entry_reference": levels.get("entry_reference"),
            "target_price": levels.get("target_price"),
            "invalidation_price": levels.get("invalidation_price"),
            "risk_distance_pct": levels.get("risk_distance_pct"),
            "reward_risk_ratio": levels.get("reward_risk_ratio"),
            "research_context": {
                "source": research_context.get("source"),
                "status": research_context.get("status"),
                "council_id": research_context.get("council_id"),
                "pipeline_run_id": research_context.get("pipeline_run_id"),
                "matched_items_count": matched_count,
            },
            "rationale": list(advice.get("rationale") or [])[:4],
            "risk_warnings": list(advice.get("risk_warnings") or [])[:4],
            "policy": {
                "execution_allowed": False,
                "human_confirmation_required": True,
                "advisory_only": True,
            },
        }


def _action_weight(action: str) -> float:
    if action in {"BUY", "SELL"}:
        return 20.0
    return 0.0


def _float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default
