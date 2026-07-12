from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from typing import Any

from finbot.research.briefing import FORBIDDEN_ACTION_PATTERNS, PRIORITY_RANK
from finbot.storage.sqlite_store import SQLiteStore


COUNCIL_POLICY_FLAGS = [
    "research_review_only",
    "no_trading_signal",
    "no_order_execution",
    "agent_verdicts_are_audit_artifacts",
    "chair_consensus_is_workflow_state",
]

ROLE_NAMES = {
    "evidence_auditor": "Evidence Auditor",
    "macro_context_analyst": "Macro Context Analyst",
    "market_context_analyst": "Market Context Analyst",
    "skeptic_reviewer": "Skeptic Reviewer",
    "policy_risk_reviewer": "Policy Risk Reviewer",
    "chair_arbiter": "Chair Arbiter",
}

SUPPORT_STANCES = {"support-watch", "policy-clear"}
FOLLOWUP_STANCES = {"needs-followup"}
MANUAL_STANCES = {"manual-review"}
ARCHIVE_STANCES = {"archive-background"}
BLOCKING_STANCES = {"policy-blocked"}


class ResearchReviewCouncil:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def run(
        self,
        time_window: str = "phase4.1-latest",
        limit_items: int = 20,
        clear_existing: bool = False,
        include_background: bool = False,
        pipeline_run_id: str | None = None,
        input_pipeline_run_id: str | None = None,
        brief_pipeline_run_id: str | None = None,
        watch_items_pipeline_run_id: str | None = None,
        cards_pipeline_run_id: str | None = None,
        idempotent_outputs: bool = True,
    ) -> dict[str, Any]:
        self.store.init_schema()
        if clear_existing:
            self.store.clear_research_review_verdicts()
            self.store.clear_research_councils()
        elif pipeline_run_id and idempotent_outputs:
            self.store.clear_research_review_verdicts(pipeline_run_id=pipeline_run_id)
            self.store.clear_research_councils(pipeline_run_id=pipeline_run_id)

        created_at = datetime.now(timezone.utc).isoformat()
        brief_scope = input_pipeline_run_id if brief_pipeline_run_id is None else brief_pipeline_run_id
        watch_item_scope = input_pipeline_run_id if watch_items_pipeline_run_id is None else watch_items_pipeline_run_id
        card_scope = input_pipeline_run_id if cards_pipeline_run_id is None else cards_pipeline_run_id
        brief = self._latest_brief(pipeline_run_id=brief_scope)
        watch_items = self._eligible_watch_items(self._latest_watch_items(pipeline_run_id=watch_item_scope), include_background)
        watch_items.sort(key=lambda item: (PRIORITY_RANK.get(item.get("priority") or "P3", 9), -_float(item.get("score")), item.get("headline") or ""))
        watch_items = watch_items[: max(0, limit_items)]
        cards = self._latest_cards(pipeline_run_id=card_scope)

        council_id = self._council_id(time_window, created_at, watch_items, pipeline_run_id)
        item_reviews = []
        all_verdicts = []
        for watch_item in watch_items:
            card = cards.get(watch_item.get("card_id") or "", {})
            role_verdicts = self._role_verdicts(watch_item, card)
            chair_verdict = self._chair_verdict(watch_item, role_verdicts)
            verdicts = [*role_verdicts, chair_verdict]
            for verdict in verdicts:
                verdict["council_id"] = council_id
                verdict["pipeline_run_id"] = pipeline_run_id
                verdict["verdict_id"] = self._verdict_id(council_id, watch_item, verdict["role_id"], created_at, pipeline_run_id)
                verdict["watch_item_id"] = watch_item["watch_item_id"]
                verdict["card_id"] = watch_item["card_id"]
                verdict["event_id"] = watch_item["event_id"]
                verdict["created_at"] = created_at
                self.store.insert_research_review_verdict(verdict)
            all_verdicts.extend(verdicts)
            item_reviews.append(
                {
                    "watch_item": self._compact_watch_item(watch_item),
                    "agent_verdicts": role_verdicts,
                    "discussion": self._discussion(role_verdicts, chair_verdict),
                    "chair_verdict": chair_verdict,
                    "consensus": chair_verdict["consensus"],
                }
            )

        summary = self._summary(item_reviews, all_verdicts)
        council = {
            "council_id": council_id,
            "pipeline_run_id": pipeline_run_id,
            "brief_id": brief.get("brief_id"),
            "time_window": time_window,
            "created_at": created_at,
            "status": summary["overall_status"],
            "summary": summary,
            "items": item_reviews,
            "policy_flags": COUNCIL_POLICY_FLAGS,
        }
        council["policy_gate"] = self._policy_gate(council)
        if council["policy_gate"]["status"] == "blocked":
            council["status"] = "blocked-by-policy"
            council["summary"]["overall_status"] = "blocked-by-policy"

        self.store.insert_research_council(council)
        return {
            "generated_at": created_at,
            "council_id": council_id,
            "brief_id": council.get("brief_id"),
            "reviewed_items": len(item_reviews),
            "summary": council["summary"],
            "policy_gate": council["policy_gate"],
            "council": council,
        }

    def _role_verdicts(self, watch_item: dict[str, Any], card: dict[str, Any]) -> list[dict[str, Any]]:
        return [
            self._evidence_auditor(watch_item),
            self._macro_context_analyst(watch_item, card),
            self._market_context_analyst(watch_item, card),
            self._skeptic_reviewer(watch_item, card),
            self._policy_risk_reviewer(watch_item, card),
        ]

    def _evidence_auditor(self, watch_item: dict[str, Any]) -> dict[str, Any]:
        evidence = watch_item.get("evidence_summary") or {}
        validation_status = watch_item.get("validation_status")
        validation_score = _float(watch_item.get("validation_score"))
        freshness_status = watch_item.get("freshness_status")
        fresh_count = _int(evidence.get("fresh_evidence_count"))
        source_count = _int(evidence.get("source_count"))
        document_count = _int(evidence.get("document_count"))
        missing_count = _int(evidence.get("missing_evidence_count"))

        concerns = []
        requests = []
        stance = "support-watch"
        severity = "info"
        confidence = 0.74
        rationale = "Validation and evidence references are sufficient for research watch."

        if validation_status == "failed":
            stance = "manual-review"
            severity = "blocker"
            confidence = 0.96
            rationale = "Validation failed; this item cannot pass council review."
            requests.append("Repair validation findings and rebuild the research card.")
        elif freshness_status != "fresh" or fresh_count <= 0:
            stance = "needs-followup"
            severity = "warning"
            confidence = 0.88
            rationale = "Current evidence is missing or not fresh enough for review approval."
            requests.append("Refresh primary evidence before the item is reused.")
        elif source_count < 2 or document_count < 2:
            stance = "needs-followup"
            severity = "warning"
            confidence = 0.82
            rationale = "Evidence breadth is thin for a council-approved research item."
            requests.append("Add an independent corroborating source.")
        elif missing_count > 0:
            stance = "needs-followup"
            severity = "warning"
            confidence = 0.78
            rationale = "The card still lists missing evidence."
            requests.append("Resolve listed missing evidence items.")

        if validation_status == "warning":
            concerns.append("Validation warning remains.")
            confidence = min(confidence, 0.76)
        if validation_score and validation_score < 90:
            concerns.append(f"Validation score is {validation_score:.2f}.")

        return _verdict("evidence_auditor", stance, confidence, severity, rationale, concerns, requests)

    def _macro_context_analyst(self, watch_item: dict[str, Any], card: dict[str, Any]) -> dict[str, Any]:
        impact_channels = watch_item.get("impact_channels") or card.get("impact_channels") or []
        macro_context = card.get("macro_context") or card.get("macro_release_facts") or []
        priority = watch_item.get("priority") or "P3"
        has_macro_like_channel = any(_channel_text(channel) for channel in impact_channels)

        concerns = []
        requests = []
        if not impact_channels:
            return _verdict(
                "macro_context_analyst",
                "needs-followup",
                0.8,
                "warning",
                "No impact channel is attached, so the macro path is not explicit.",
                ["Impact channel is missing."],
                ["Attach impact channels before downstream review reuse."],
            )

        if priority in {"P0", "P1"} and not macro_context:
            concerns.append("High-priority item has no structured macro context attached.")
            requests.append("Attach macro release facts or explicit policy context if available.")
            return _verdict(
                "macro_context_analyst",
                "needs-followup",
                0.72,
                "warning",
                "The event has impact channels, but high-priority macro context is incomplete.",
                concerns,
                requests,
            )

        rationale = "Impact channels give a usable macro review path."
        if has_macro_like_channel:
            rationale = "Impact channels are explicit enough for macro review routing."
        return _verdict("macro_context_analyst", "support-watch", 0.68, "info", rationale, concerns, requests)

    def _market_context_analyst(self, watch_item: dict[str, Any], card: dict[str, Any]) -> dict[str, Any]:
        market_context = card.get("market_context") or []
        followup = watch_item.get("followup") or {}
        queued_jobs = _int(followup.get("queued_jobs"))
        decision = watch_item.get("decision")
        concerns = []
        requests = []

        if not market_context:
            concerns.append("Market context snapshot is missing.")
            requests.append("Run market confirmation or attach a neutral context snapshot.")
            return _verdict(
                "market_context_analyst",
                "needs-followup",
                0.82,
                "warning",
                "Market context is not attached, so review should remain operational.",
                concerns,
                requests,
            )

        if decision == "needs-followup" and queued_jobs > 0:
            concerns.append(f"Queued follow-up jobs remain: {queued_jobs}.")
            requests.append("Run queued follow-up jobs and rebuild P3/P4.")
            return _verdict(
                "market_context_analyst",
                "needs-followup",
                0.74,
                "warning",
                "Market context exists, but queued follow-up work is still open.",
                concerns,
                requests,
            )

        return _verdict(
            "market_context_analyst",
            "support-watch",
            0.7,
            "info",
            "Neutral market context is attached for research review.",
            concerns,
            requests,
        )

    def _skeptic_reviewer(self, watch_item: dict[str, Any], card: dict[str, Any]) -> dict[str, Any]:
        risks = list(watch_item.get("research_risks") or [])
        risks.extend(str(value) for value in card.get("counter_arguments") or [])
        missing_evidence = card.get("missing_evidence") or []
        ai_refs = card.get("ai_compression_refs") or []
        concerns = list(dict.fromkeys(risks[:6]))
        requests = []

        if missing_evidence:
            requests.append("Close missing evidence items before council approval.")
            return _verdict(
                "skeptic_reviewer",
                "needs-followup",
                0.88,
                "warning",
                "The skeptical review found unresolved evidence gaps.",
                concerns,
                requests,
            )
        if len(concerns) >= 3:
            requests.append("Resolve the strongest counter-arguments or mark the card manual-review.")
            return _verdict(
                "skeptic_reviewer",
                "manual-review",
                0.76,
                "warning",
                "Several counter-arguments remain unresolved.",
                concerns,
                requests,
            )
        if ai_refs:
            concerns.append("AI compression is context only and should not be treated as evidence.")

        return _verdict(
            "skeptic_reviewer",
            "support-watch",
            0.64,
            "info",
            "No blocking counter-argument was found by the skeptical review.",
            concerns,
            requests,
        )

    def _policy_risk_reviewer(self, watch_item: dict[str, Any], card: dict[str, Any]) -> dict[str, Any]:
        fragments = [
            str(watch_item.get("headline") or ""),
            str(watch_item.get("next_action") or ""),
            str(card.get("summary") or ""),
            " ".join(str(value) for value in card.get("counter_arguments") or []),
        ]
        text = "\n".join(fragments).lower()
        hits = sorted({pattern for pattern in FORBIDDEN_ACTION_PATTERNS if re.search(pattern, text)})
        if hits:
            return _verdict(
                "policy_risk_reviewer",
                "policy-blocked",
                0.98,
                "blocker",
                "Forbidden trading-action language was detected in review material.",
                [f"Matched policy pattern: {pattern}" for pattern in hits],
                ["Remove trading-action language and rebuild the card before reuse."],
            )
        return _verdict(
            "policy_risk_reviewer",
            "policy-clear",
            0.93,
            "info",
            "Research-only policy boundary is preserved.",
            [],
            [],
        )

    def _chair_verdict(self, watch_item: dict[str, Any], verdicts: list[dict[str, Any]]) -> dict[str, Any]:
        stances = Counter(verdict["stance"] for verdict in verdicts)
        reasons = []
        status = "watch-approved"
        severity = "info"

        if any(verdict["stance"] in BLOCKING_STANCES for verdict in verdicts):
            status = "blocked-by-policy"
            severity = "blocker"
            reasons.append("Policy reviewer blocked the item.")
        elif stances["manual-review"] >= 2 or watch_item.get("validation_status") == "failed":
            status = "manual-review"
            severity = "warning"
            reasons.append("Multiple reviewers requested manual review.")
        elif stances["needs-followup"] >= 2 or watch_item.get("decision") == "needs-followup":
            status = "needs-followup"
            severity = "warning"
            reasons.append("Follow-up work remains before council approval.")
        elif stances["archive-background"] >= 2 or watch_item.get("decision") == "archive-background":
            status = "archive-background"
            severity = "info"
            reasons.append("The item is better kept as background context.")
        elif stances["support-watch"] + stances["policy-clear"] >= 4:
            reasons.append("Most reviewers support research watch usage.")
        else:
            status = "manual-review"
            severity = "warning"
            reasons.append("Reviewer positions did not reach a clean support consensus.")

        dissenting_roles = [verdict["role_id"] for verdict in verdicts if not _stance_matches_status(verdict["stance"], status)]
        confidence = _average(verdict["confidence"] for verdict in verdicts)
        confidence = max(0.0, min(0.99, confidence - len(dissenting_roles) * 0.03))
        next_action = _next_action_for_status(status)
        consensus = {
            "status": status,
            "confidence": round(confidence, 2),
            "severity": severity,
            "reasons": reasons,
            "dissenting_roles": dissenting_roles,
            "next_action": next_action,
        }
        return _verdict(
            "chair_arbiter",
            status,
            consensus["confidence"],
            severity,
            "Chair aggregated role verdicts into a workflow consensus.",
            reasons,
            [next_action],
            consensus=consensus,
        )

    def _discussion(self, verdicts: list[dict[str, Any]], chair_verdict: dict[str, Any]) -> dict[str, Any]:
        opening_statements = [
            {
                "role_id": verdict["role_id"],
                "stance": verdict["stance"],
                "point": verdict["rationale"],
            }
            for verdict in verdicts
        ]
        challenges = [
            {
                "role_id": verdict["role_id"],
                "concerns": verdict["concerns"],
            }
            for verdict in verdicts
            if verdict.get("concerns")
        ]
        return {
            "rounds": [
                {
                    "round": "opening-verdicts",
                    "items": opening_statements,
                },
                {
                    "round": "challenge-and-rebuttal",
                    "items": challenges,
                },
                {
                    "round": "chair-consensus",
                    "items": [
                        {
                            "role_id": "chair_arbiter",
                            "stance": chair_verdict["stance"],
                            "point": chair_verdict["consensus"]["next_action"],
                        }
                    ],
                },
            ],
            "dissenting_roles": chair_verdict["consensus"]["dissenting_roles"],
        }

    def _summary(self, item_reviews: list[dict[str, Any]], verdicts: list[dict[str, Any]]) -> dict[str, Any]:
        consensus_statuses = Counter(review["consensus"]["status"] for review in item_reviews)
        role_stances = Counter(verdict["stance"] for verdict in verdicts if verdict["role_id"] != "chair_arbiter")
        priorities = Counter((review["watch_item"].get("priority") or "P3") for review in item_reviews)
        overall_status = "watch-approved"
        if consensus_statuses.get("blocked-by-policy"):
            overall_status = "blocked-by-policy"
        elif consensus_statuses.get("manual-review"):
            overall_status = "manual-review"
        elif consensus_statuses.get("needs-followup"):
            overall_status = "needs-followup"
        elif not item_reviews:
            overall_status = "empty"

        return {
            "reviewed_items": len(item_reviews),
            "overall_status": overall_status,
            "consensus_statuses": dict(consensus_statuses),
            "role_stances": dict(role_stances),
            "dissenting_items": sum(1 for review in item_reviews if review["consensus"]["dissenting_roles"]),
            "top_priority": min(priorities, key=lambda value: PRIORITY_RANK.get(value, 9)) if priorities else None,
            "policy_blocked_items": consensus_statuses.get("blocked-by-policy", 0),
        }

    def _policy_gate(self, council: dict[str, Any]) -> dict[str, Any]:
        fragments: list[str] = []
        for review in council.get("items") or []:
            watch_item = review.get("watch_item") or {}
            fragments.append(str(watch_item.get("headline") or ""))
            for verdict in review.get("agent_verdicts") or []:
                fragments.append(str(verdict.get("rationale") or ""))
                fragments.extend(str(value) for value in verdict.get("concerns") or [])
                fragments.extend(str(value) for value in verdict.get("requested_evidence") or [])
            consensus = review.get("consensus") or {}
            fragments.append(str(consensus.get("next_action") or ""))
        text = "\n".join(fragments).lower()
        hits = sorted({pattern for pattern in FORBIDDEN_ACTION_PATTERNS if re.search(pattern, text)})
        return {
            "status": "passed" if not hits else "blocked",
            "forbidden_hits": hits,
            "rules": [
                "P4.1 council output is research workflow state only.",
                "P4.1 council output must not contain trading actions.",
                "P4.1 agent verdicts must remain auditable and reproducible.",
            ],
        }

    def _latest_brief(self, pipeline_run_id: str | None = None) -> dict[str, Any]:
        rows = self.store.list_research_briefs(limit=1, pipeline_run_id=pipeline_run_id)
        if not rows:
            return {}
        return _loads(rows[0]["payload_json"], {})

    def _latest_watch_items(self, pipeline_run_id: str | None = None) -> list[dict[str, Any]]:
        rows = self.store.list_research_watch_items(limit=None, pipeline_run_id=pipeline_run_id)
        latest_by_card: dict[str, dict[str, Any]] = {}
        for row in rows:
            if row["card_id"] in latest_by_card:
                continue
            item = _loads(row["payload_json"], {})
            if item:
                latest_by_card[row["card_id"]] = item
        return list(latest_by_card.values())

    def _latest_cards(self, pipeline_run_id: str | None = None) -> dict[str, dict[str, Any]]:
        latest: dict[str, dict[str, Any]] = {}
        for row in self.store.list_research_cards(limit=None, pipeline_run_id=pipeline_run_id):
            if row["card_id"] in latest:
                continue
            latest[row["card_id"]] = _loads(row["payload_json"], {})
        return latest

    def _eligible_watch_items(self, watch_items: list[dict[str, Any]], include_background: bool) -> list[dict[str, Any]]:
        if include_background:
            return watch_items
        return [item for item in watch_items if item.get("status") != "background-archive" and item.get("decision") != "archive-background"]

    def _compact_watch_item(self, watch_item: dict[str, Any]) -> dict[str, Any]:
        return {
            "watch_item_id": watch_item.get("watch_item_id"),
            "card_id": watch_item.get("card_id"),
            "event_id": watch_item.get("event_id"),
            "event_key": watch_item.get("event_key"),
            "headline": watch_item.get("headline"),
            "decision": watch_item.get("decision"),
            "status": watch_item.get("status"),
            "priority": watch_item.get("priority"),
            "score": watch_item.get("score"),
            "validation_status": watch_item.get("validation_status"),
            "freshness_status": watch_item.get("freshness_status"),
        }

    def _council_id(self, time_window: str, created_at: str, watch_items: list[dict[str, Any]], pipeline_run_id: str | None) -> str:
        ids = ",".join(str(item.get("watch_item_id") or "") for item in watch_items)
        if pipeline_run_id:
            value = f"phase4.1-council:{pipeline_run_id}:{time_window}:{ids}"
        else:
            value = f"phase4.1-council:{time_window}:{created_at}:{ids}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def _verdict_id(self, council_id: str, watch_item: dict[str, Any], role_id: str, created_at: str, pipeline_run_id: str | None) -> str:
        if pipeline_run_id:
            value = f"phase4.1-verdict:{pipeline_run_id}:{council_id}:{watch_item.get('watch_item_id')}:{role_id}"
        else:
            value = f"phase4.1-verdict:{council_id}:{watch_item.get('watch_item_id')}:{role_id}:{created_at}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _verdict(
    role_id: str,
    stance: str,
    confidence: float,
    severity: str,
    rationale: str,
    concerns: list[str],
    requested_evidence: list[str],
    consensus: dict[str, Any] | None = None,
) -> dict[str, Any]:
    verdict = {
        "role_id": role_id,
        "role_name": ROLE_NAMES[role_id],
        "stance": stance,
        "confidence": round(max(0.0, min(0.99, confidence)), 2),
        "severity": severity,
        "rationale": rationale,
        "concerns": concerns,
        "requested_evidence": requested_evidence,
        "policy_note": "Research workflow review only; no trading action is generated.",
    }
    if consensus is not None:
        verdict["consensus"] = consensus
    return verdict


def _stance_matches_status(stance: str, status: str) -> bool:
    if stance == "policy-clear" and status != "blocked-by-policy":
        return True
    if status == "watch-approved":
        return stance in SUPPORT_STANCES
    if status == "needs-followup":
        return stance in FOLLOWUP_STANCES
    if status == "manual-review":
        return stance in MANUAL_STANCES
    if status == "archive-background":
        return stance in ARCHIVE_STANCES
    if status == "blocked-by-policy":
        return stance in BLOCKING_STANCES
    return False


def _next_action_for_status(status: str) -> str:
    if status == "watch-approved":
        return "Keep this item in research watch and refresh on the next scheduled source run."
    if status == "needs-followup":
        return "Complete queued follow-up work, then rebuild and revalidate P3/P4 artifacts."
    if status == "manual-review":
        return "Route this item to manual research review before reuse."
    if status == "archive-background":
        return "Keep this item as background context until fresh evidence appears."
    if status == "blocked-by-policy":
        return "Remove policy-violating language and rebuild the research card."
    return "Review workflow state before reuse."


def _channel_text(channel: Any) -> str:
    if isinstance(channel, dict):
        return " ".join(str(value) for value in channel.values() if value)
    return str(channel or "")


def _average(values: Any) -> float:
    numbers = [_float(value) for value in values]
    if not numbers:
        return 0.0
    return sum(numbers) / len(numbers)


def _float(value: Any) -> float:
    try:
        return float(value or 0.0)
    except (TypeError, ValueError):
        return 0.0


def _int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
