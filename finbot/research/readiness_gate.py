from __future__ import annotations

from collections import Counter
from typing import Any


READY_GROUPS = (
    "research-ready",
    "needs-corroboration",
    "watch-only",
    "discard-or-background",
)

BACKGROUND_FLAGS = {
    "generic_landing_or_index_page",
    "weak_topic_match",
}

CORROBORATION_FLAGS = {
    "single_source",
    "no_t1_official_confirmation",
    "missing_market_confirmation",
    "needs_conflict_review",
}


class ResearchReadinessGate:
    def annotate(self, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
        annotated: list[dict[str, Any]] = []
        for event in events:
            readiness, reasons = self.classify(event)
            value = dict(event)
            value["research_readiness"] = readiness
            value["readiness_reasons"] = reasons
            annotated.append(value)
        return annotated

    def group(self, events: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
        annotated = self.annotate(events)
        groups = {group: [] for group in READY_GROUPS}
        for event in annotated:
            groups[event["research_readiness"]].append(event)
        return groups

    def summary(self, events: list[dict[str, Any]]) -> dict[str, Any]:
        annotated = self.annotate(events)
        readiness = Counter(event["research_readiness"] for event in annotated)
        reasons = Counter(reason for event in annotated for reason in event["readiness_reasons"])
        return {
            "readiness": {group: readiness.get(group, 0) for group in READY_GROUPS},
            "top_reasons": dict(sorted(reasons.items())),
        }

    def classify(self, event: dict[str, Any]) -> tuple[str, list[str]]:
        quality = event.get("quality") or {}
        score = float(quality.get("score") or 0.0)
        priority = event.get("priority") or "P3"
        state = event.get("confirmation_state") or "weak"
        flags = set(quality.get("review_flags") or [])
        market_status = (event.get("market_confirmation") or {}).get("status")

        reasons: list[str] = []
        background = sorted(flags.intersection(BACKGROUND_FLAGS))
        if background:
            reasons.extend(background)
            return "discard-or-background", reasons

        if score < 0.30:
            return "discard-or-background", ["quality_score_below_0_30"]

        conflict_or_social = flags.intersection({"needs_conflict_review", "social_only"})
        if priority == "P3" or state == "weak":
            reasons.extend(sorted(conflict_or_social) or ["weak_confirmation_state"])
            return "watch-only" if score >= 0.35 else "discard-or-background", reasons

        missing_evidence = sorted(flags.intersection(CORROBORATION_FLAGS))
        if missing_evidence:
            reasons.extend(missing_evidence)
            if priority in {"P0", "P1"} or score >= 0.50:
                return "needs-corroboration", reasons
            return "watch-only", reasons

        if state in {"confirmed-by-official-and-secondary", "secondary-confirmed", "official-only", "likely"} and score >= 0.62:
            if market_status in {"available", "not-applicable"} or priority in {"P0", "P1"}:
                return "research-ready", ["quality_and_confirmation_ready"]
            return "needs-corroboration", ["missing_market_confirmation"]

        if score >= 0.45:
            return "watch-only", ["watch_quality_threshold_met"]
        return "discard-or-background", ["quality_score_below_watch_threshold"]


def payload_group_key(readiness: str) -> str:
    return {
        "research-ready": "research_ready_events",
        "needs-corroboration": "needs_corroboration_events",
        "watch-only": "watch_only_events",
        "discard-or-background": "discarded_or_background_events",
    }[readiness]
