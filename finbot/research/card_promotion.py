from __future__ import annotations

import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


PROMOTION_POLICY_FLAGS = [
    "research_workflow_only",
    "no_trading_signal",
    "validated_cards_only",
    "needs_corroboration_is_not_research_ready",
]

DECISIONS = ("active-watch", "needs-followup", "manual-review", "archive-background")


class ResearchCardPromoter:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def promote_all(
        self,
        limit: int | None = None,
        clear_existing: bool = False,
        pipeline_run_id: str | None = None,
        input_pipeline_run_id: str | None = None,
        cards_pipeline_run_id: str | None = None,
        validations_pipeline_run_id: str | None = None,
        idempotent_outputs: bool = True,
    ) -> dict[str, Any]:
        if clear_existing:
            self.store.clear_research_card_decisions()
        elif pipeline_run_id and idempotent_outputs:
            self.store.clear_research_card_decisions(pipeline_run_id=pipeline_run_id)
        card_scope = input_pipeline_run_id if cards_pipeline_run_id is None else cards_pipeline_run_id
        validation_scope = input_pipeline_run_id if validations_pipeline_run_id is None else validations_pipeline_run_id
        validations = self._latest_validations(pipeline_run_id=validation_scope)
        rows = self.store.list_research_cards(limit=limit, pipeline_run_id=card_scope)
        decisions = []
        skipped = []
        for row in rows:
            card = _loads(row["payload_json"], {})
            validation = validations.get(row["card_id"])
            if validation is None:
                skipped.append({"card_id": row["card_id"], "reason": "missing_validation"})
                continue
            if validation["status"] == "failed":
                skipped.append({"card_id": row["card_id"], "reason": "validation_failed"})
                continue
            decision = self.promote_card(card, validation, pipeline_run_id=pipeline_run_id)
            self.store.insert_research_card_decision(decision)
            decisions.append(decision)
        return {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "total_cards": len(rows),
            "promoted_cards": len(decisions),
            "skipped_cards": skipped,
            "decisions": dict(Counter(decision["decision"] for decision in decisions)),
            "policy_flags": PROMOTION_POLICY_FLAGS,
            "items": decisions,
        }

    def promote_card(self, card: dict[str, Any], validation: dict[str, Any], pipeline_run_id: str | None = None) -> dict[str, Any]:
        score, reasons = self._score(card, validation)
        decision = self._decision(card, validation, score, reasons)
        created_at = datetime.now(timezone.utc).isoformat()
        return {
            "decision_id": self._decision_id(card, created_at, pipeline_run_id),
            "pipeline_run_id": pipeline_run_id,
            "card_id": card["card_id"],
            "event_id": card["event_id"],
            "event_key": card["event_key"],
            "decision": decision,
            "score": score,
            "readiness": card.get("readiness"),
            "priority": card.get("priority"),
            "freshness_status": card.get("freshness_status"),
            "validation_status": validation["status"],
            "validation_score": validation["score"],
            "reasons": reasons,
            "watchlist_tags": self._watchlist_tags(card, decision),
            "follow_up_jobs": self._follow_up_jobs(card, decision),
            "policy_flags": PROMOTION_POLICY_FLAGS,
            "created_at": created_at,
        }

    def _score(self, card: dict[str, Any], validation: dict[str, Any]) -> tuple[float, list[str]]:
        reasons: list[str] = []
        evidence = card.get("evidence_assessment") or {}
        freshness = card.get("freshness") or {}
        quality_score = float(evidence.get("quality_score") or 0.0)
        validation_score = float(validation.get("score") or 0.0)
        missing_evidence = card.get("missing_evidence") or []
        market_context = card.get("market_context") or []
        ai_compressions = card.get("ai_compression_refs") or []
        follow_up_jobs = card.get("follow_up_jobs") or []

        score = quality_score * 55.0 + validation_score * 0.25

        priority_bonus = {"P0": 18.0, "P1": 12.0, "P2": 6.0, "P3": 0.0}.get(card.get("priority") or "P3", 0.0)
        if priority_bonus:
            reasons.append(f"priority_bonus:{card.get('priority')}")
        score += priority_bonus

        readiness = card.get("readiness")
        if readiness == "research-ready":
            score += 12.0
            reasons.append("readiness:research-ready")
        elif readiness == "needs-corroboration":
            score += 5.0
            reasons.append("readiness:needs-corroboration")

        freshness_status = card.get("freshness_status")
        if freshness_status == "fresh":
            score += 10.0
            reasons.append("fresh_evidence_available")
        elif freshness_status == "stale-context-only":
            score -= 20.0
            reasons.append("stale_context_only")
        else:
            score -= 35.0
            reasons.append("no_current_evidence")

        if market_context:
            score += 6.0
            reasons.append("market_context_attached")
        else:
            score -= 4.0
            reasons.append("missing_market_context")

        if ai_compressions:
            score += 3.0
            reasons.append("ai_compression_available")

        if missing_evidence:
            penalty = min(18.0, len(missing_evidence) * 4.0)
            score -= penalty
            reasons.append(f"missing_evidence:{len(missing_evidence)}")

        if follow_up_jobs:
            reasons.append(f"follow_up_jobs:{len(follow_up_jobs)}")

        if validation["status"] == "warning":
            score -= 10.0
            reasons.append("validation_warning")

        return round(max(0.0, min(100.0, score)), 2), reasons

    def _decision(self, card: dict[str, Any], validation: dict[str, Any], score: float, reasons: list[str]) -> str:
        if validation["status"] == "warning":
            return "manual-review"
        if card.get("freshness_status") == "no-current-evidence":
            return "archive-background"
        if card.get("freshness_status") == "stale-context-only":
            return "needs-followup"
        if card.get("readiness") == "research-ready" and score >= 70:
            return "active-watch"
        if card.get("readiness") == "needs-corroboration":
            return "needs-followup"
        if score >= 65:
            return "active-watch"
        if score >= 40:
            return "needs-followup"
        if "validation_warning" in reasons:
            return "manual-review"
        return "archive-background"

    def _watchlist_tags(self, card: dict[str, Any], decision: str) -> list[str]:
        tags = [decision, card.get("readiness") or "unknown-readiness", card.get("freshness_status") or "unknown-freshness"]
        for channel in card.get("impact_channels") or []:
            asset = channel.get("asset")
            if asset and asset not in tags:
                tags.append(asset)
            value = channel.get("channel")
            if value and value not in tags:
                tags.append(value)
        priority = card.get("priority")
        if priority:
            tags.append(priority)
        return tags

    def _follow_up_jobs(self, card: dict[str, Any], decision: str) -> list[dict[str, Any]]:
        if decision not in {"needs-followup", "manual-review"}:
            return []
        return list(card.get("follow_up_jobs") or [])

    def _latest_validations(self, pipeline_run_id: str | None = None) -> dict[str, dict[str, Any]]:
        latest: dict[str, dict[str, Any]] = {}
        for row in self.store.list_research_card_validations(limit=None, pipeline_run_id=pipeline_run_id):
            if row["card_id"] in latest:
                continue
            latest[row["card_id"]] = {
                "validation_id": row["validation_id"],
                "card_id": row["card_id"],
                "event_id": row["event_id"],
                "status": row["status"],
                "score": row["score"],
                "findings": _loads(row["findings_json"], []),
                "created_at": row["created_at"],
            }
        return latest

    def _decision_id(self, card: dict[str, Any], created_at: str, pipeline_run_id: str | None) -> str:
        if pipeline_run_id:
            value = f"{pipeline_run_id}:{card.get('card_id')}:{card.get('event_id')}:promotion-v1"
        else:
            value = f"{card.get('card_id')}:{card.get('event_id')}:{created_at}:promotion-v1"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
