from __future__ import annotations

import hashlib
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


PHASE4_POLICY_FLAGS = [
    "research_operations_only",
    "no_trading_signal",
    "no_order_execution",
    "validated_research_cards_only",
    "operator_actions_are_workflow_tasks",
]

FORBIDDEN_ACTION_PATTERNS = (
    r"\bbuy\b",
    r"\bsell\b",
    r"\btarget price\b",
    r"\bstop loss\b",
    r"\btake profit\b",
    r"\bentry price\b",
    r"\bexit price\b",
    r"\bopen position\b",
    r"\bclose position\b",
)

DECISION_STATUS = {
    "active-watch": "active-watch",
    "needs-followup": "pending-followup",
    "manual-review": "manual-review",
    "archive-background": "background-archive",
}

PRIORITY_RANK = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}


class ResearchBriefBuilder:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def build(
        self,
        time_window: str = "phase4-latest",
        limit_items: int = 20,
        clear_existing: bool = False,
        pipeline_run_id: str | None = None,
        input_pipeline_run_id: str | None = None,
        cards_pipeline_run_id: str | None = None,
        validations_pipeline_run_id: str | None = None,
        decisions_pipeline_run_id: str | None = None,
        dispatches_pipeline_run_id: str | None = None,
        idempotent_outputs: bool = True,
    ) -> dict[str, Any]:
        self.store.init_schema()
        if clear_existing:
            self.store.clear_research_watch_items()
            self.store.clear_research_briefs()
        elif pipeline_run_id and idempotent_outputs:
            self.store.clear_research_watch_items(pipeline_run_id=pipeline_run_id)
            self.store.clear_research_briefs(pipeline_run_id=pipeline_run_id)

        card_scope = input_pipeline_run_id if cards_pipeline_run_id is None else cards_pipeline_run_id
        validation_scope = input_pipeline_run_id if validations_pipeline_run_id is None else validations_pipeline_run_id
        decision_scope = input_pipeline_run_id if decisions_pipeline_run_id is None else decisions_pipeline_run_id
        dispatch_scope = input_pipeline_run_id if dispatches_pipeline_run_id is None else dispatches_pipeline_run_id
        cards = self._latest_cards(pipeline_run_id=card_scope)
        validations = self._latest_validations(pipeline_run_id=validation_scope)
        decisions = self._latest_decisions(pipeline_run_id=decision_scope)
        dispatch_stats = self._dispatch_stats(pipeline_run_id=dispatch_scope)
        watch_items = self._watch_items(time_window, cards, validations, decisions, dispatch_stats, pipeline_run_id)
        watch_items.sort(key=lambda item: (PRIORITY_RANK.get(item.get("priority") or "P3", 9), -float(item["score"]), item["headline"]))
        watch_items = watch_items[:limit_items]

        for item in watch_items:
            self.store.insert_research_watch_item(item)

        source_health = self._source_health()
        budget_state = self._budget_state()
        followup_queue = self._followup_queue()
        operator_actions = self._operator_actions(watch_items, source_health, budget_state, followup_queue)
        created_at = datetime.now(timezone.utc).isoformat()
        brief = {
            "brief_id": self._brief_id(time_window, created_at, watch_items, pipeline_run_id),
            "pipeline_run_id": pipeline_run_id,
            "time_window": time_window,
            "created_at": created_at,
            "summary": self._summary(watch_items, source_health, budget_state, followup_queue),
            "watch_items": watch_items,
            "followup_queue": followup_queue,
            "source_blockers": source_health,
            "budget_state": budget_state,
            "operator_actions": operator_actions,
            "policy_flags": PHASE4_POLICY_FLAGS,
        }
        brief["policy_gate"] = self._policy_gate(brief)
        self.store.insert_research_brief(brief)
        return {
            "generated_at": created_at,
            "time_window": time_window,
            "watch_items_created": len(watch_items),
            "brief_id": brief["brief_id"],
            "summary": brief["summary"],
            "policy_gate": brief["policy_gate"],
            "brief": brief,
        }

    def _watch_items(
        self,
        time_window: str,
        cards: dict[str, dict[str, Any]],
        validations: dict[str, dict[str, Any]],
        decisions: dict[str, dict[str, Any]],
        dispatch_stats: dict[str, dict[str, Any]],
        pipeline_run_id: str | None,
    ) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for card_id, decision in decisions.items():
            card = cards.get(card_id)
            validation = validations.get(card_id)
            if not card or not validation:
                continue
            decision_name = str(decision.get("decision") or "manual-review")
            created_at = datetime.now(timezone.utc).isoformat()
            item = {
                "watch_item_id": self._watch_item_id(time_window, decision, card, pipeline_run_id),
                "pipeline_run_id": pipeline_run_id,
                "card_id": card_id,
                "event_id": card.get("event_id"),
                "event_key": card.get("event_key"),
                "headline": self._headline(card),
                "decision": decision_name,
                "status": DECISION_STATUS.get(decision_name, "manual-review"),
                "priority": decision.get("priority") or card.get("priority") or "P3",
                "score": float(decision.get("score") or 0.0),
                "readiness": card.get("readiness"),
                "freshness_status": card.get("freshness_status"),
                "validation_status": validation.get("status"),
                "validation_score": validation.get("score"),
                "impact_channels": card.get("impact_channels") or [],
                "evidence_summary": self._evidence_summary(card),
                "research_risks": self._research_risks(card),
                "followup": dispatch_stats.get(card_id, {"queued_jobs": 0, "source_ids": {}, "job_types": {}}),
                "next_action": self._next_action(decision_name, card, validation),
                "policy_note": "Research workflow item only; no trading action is generated.",
                "created_at": created_at,
            }
            items.append(item)
        return items

    def _summary(
        self,
        watch_items: list[dict[str, Any]],
        source_health: dict[str, Any],
        budget_state: dict[str, Any],
        followup_queue: dict[str, Any],
    ) -> dict[str, Any]:
        decisions = Counter(item["decision"] for item in watch_items)
        priorities = Counter(item.get("priority") or "P3" for item in watch_items)
        statuses = Counter(item["status"] for item in watch_items)
        return {
            "total_watch_items": len(watch_items),
            "decisions": dict(decisions),
            "statuses": dict(statuses),
            "priorities": dict(priorities),
            "queued_followups": followup_queue["total_queued"],
            "credential_blockers": len(source_health["credential_blockers"]),
            "provider_blockers": len(source_health["provider_blockers"]),
            "throttled_sources": len(budget_state["throttled_sources"]),
            "top_priority": min(priorities, key=lambda value: PRIORITY_RANK.get(value, 9)) if priorities else None,
        }

    def _operator_actions(
        self,
        watch_items: list[dict[str, Any]],
        source_health: dict[str, Any],
        budget_state: dict[str, Any],
        followup_queue: dict[str, Any],
    ) -> list[dict[str, str]]:
        actions: list[dict[str, str]] = []
        if followup_queue["total_queued"]:
            actions.append(
                {
                    "type": "run-followups",
                    "priority": "P1",
                    "detail": "Run queued research follow-ups in small batches with provider backoff enabled.",
                }
            )
        if budget_state["throttled_sources"]:
            actions.append(
                {
                    "type": "wait-throttle",
                    "priority": "P1",
                    "detail": "Wait for throttled sources to clear before scheduling additional follow-up work for those sources.",
                }
            )
        if source_health["credential_blockers"]:
            actions.append(
                {
                    "type": "configure-credentials",
                    "priority": "P2",
                    "detail": "Configure missing provider credentials or keep affected sources disabled in research plans.",
                }
            )
        if any(item["decision"] == "manual-review" for item in watch_items):
            actions.append(
                {
                    "type": "manual-review",
                    "priority": "P1",
                    "detail": "Review manual-review cards before they are reused in downstream research briefs.",
                }
            )
        if any(item["decision"] == "needs-followup" for item in watch_items):
            actions.append(
                {
                    "type": "review-after-evidence",
                    "priority": "P1",
                    "detail": "Rebuild and validate research cards after new evidence lands.",
                }
            )
        if not actions:
            actions.append(
                {
                    "type": "scheduled-refresh",
                    "priority": "P3",
                    "detail": "No immediate operator action beyond scheduled source refresh.",
                }
            )
        return actions

    def _source_health(self) -> dict[str, Any]:
        with self.store.connect() as conn:
            rows = conn.execute("select * from source_health order by source_id").fetchall()
        credential_blockers = []
        provider_blockers = []
        disabled_sources = []
        for row in rows:
            item = {
                "source_id": row["source_id"],
                "status": row["status"],
                "detail": row["detail"],
                "required_keys": _loads(row["required_keys_json"], []),
            }
            if row["status"] == "blocked-by-credential":
                credential_blockers.append(item)
            elif row["status"] == "blocked-by-provider":
                provider_blockers.append(item)
            elif row["status"].startswith("disabled"):
                disabled_sources.append(item)
        return {
            "credential_blockers": credential_blockers,
            "provider_blockers": provider_blockers,
            "disabled_sources": disabled_sources,
        }

    def _budget_state(self) -> dict[str, Any]:
        rows = self.store.list_source_budget_state()
        throttled = []
        near_budget = []
        for row in rows:
            item = {
                "source_id": row["source_id"],
                "provider": row["provider"],
                "status": row["status"],
                "throttled_until": row["throttled_until"],
                "last_error": row["last_error"],
            }
            if row["status"] in {"throttled", "budget-exhausted"} or row["throttled_until"]:
                throttled.append(item)
            elif row["status"] == "near-budget":
                near_budget.append(item)
        return {
            "throttled_sources": throttled,
            "near_budget_sources": near_budget,
        }

    def _followup_queue(self) -> dict[str, Any]:
        with self.store.connect() as conn:
            rows = conn.execute(
                """
                select status, source_id, job_type, count(*) as count
                from fetch_jobs
                where status is not null
                group by status, source_id, job_type
                order by status, source_id, job_type
                """
            ).fetchall()
        by_status: dict[str, int] = defaultdict(int)
        by_source: dict[str, int] = defaultdict(int)
        by_type: dict[str, int] = defaultdict(int)
        details = []
        for row in rows:
            count = int(row["count"])
            status = row["status"] or "unknown"
            source_id = row["source_id"]
            job_type = row["job_type"]
            by_status[status] += count
            by_source[source_id] += count
            by_type[job_type] += count
            details.append({"status": status, "source_id": source_id, "job_type": job_type, "count": count})
        return {
            "total_queued": by_status.get("queued-research-followup", 0),
            "by_status": dict(by_status),
            "by_source": dict(by_source),
            "by_type": dict(by_type),
            "details": details,
        }

    def _dispatch_stats(self, pipeline_run_id: str | None = None) -> dict[str, dict[str, Any]]:
        stats: dict[str, dict[str, Any]] = defaultdict(lambda: {"queued_jobs": 0, "source_ids": Counter(), "job_types": Counter()})
        for row in self.store.list_research_followup_dispatches(limit=None, pipeline_run_id=pipeline_run_id):
            payload = _loads(row["payload_json"], {})
            card_id = payload.get("card_id") or row["card_id"]
            item = stats[card_id]
            item["queued_jobs"] += 1
            if payload.get("source_id"):
                item["source_ids"][payload["source_id"]] += 1
            if payload.get("job_type"):
                item["job_types"][payload["job_type"]] += 1
        return {
            card_id: {
                "queued_jobs": value["queued_jobs"],
                "source_ids": dict(value["source_ids"]),
                "job_types": dict(value["job_types"]),
            }
            for card_id, value in stats.items()
        }

    def _latest_cards(self, pipeline_run_id: str | None = None) -> dict[str, dict[str, Any]]:
        latest: dict[str, dict[str, Any]] = {}
        for row in self.store.list_research_cards(limit=None, pipeline_run_id=pipeline_run_id):
            if row["card_id"] in latest:
                continue
            latest[row["card_id"]] = _loads(row["payload_json"], {})
        return latest

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

    def _latest_decisions(self, pipeline_run_id: str | None = None) -> dict[str, dict[str, Any]]:
        latest: dict[str, dict[str, Any]] = {}
        for row in self.store.list_research_card_decisions(limit=None, pipeline_run_id=pipeline_run_id):
            if row["card_id"] in latest:
                continue
            latest[row["card_id"]] = _loads(row["payload_json"], {})
        return latest

    def _evidence_summary(self, card: dict[str, Any]) -> dict[str, Any]:
        evidence = card.get("evidence_assessment") or {}
        freshness = card.get("freshness") or {}
        return {
            "quality_score": evidence.get("quality_score"),
            "confirmation_state": evidence.get("confirmation_state"),
            "fresh_evidence_count": freshness.get("fresh_evidence_count", len(card.get("fresh_evidence") or [])),
            "stale_context_count": freshness.get("stale_context_count", len(card.get("stale_context") or [])),
            "source_count": evidence.get("source_count"),
            "document_count": evidence.get("document_count"),
            "missing_evidence_count": len(card.get("missing_evidence") or []),
        }

    def _headline(self, card: dict[str, Any]) -> str:
        raw = str(card.get("headline") or "").strip()
        headline = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", raw)
        headline = re.sub(r"\s+", " ", headline).strip()
        if not headline or headline.lower().startswith("skip to main content"):
            fallback = str(card.get("event_key") or card.get("event_id") or "research event")
            return fallback.replace(":", " / ")
        return headline

    def _research_risks(self, card: dict[str, Any]) -> list[str]:
        risks: list[str] = []
        risks.extend(str(item) for item in (card.get("counter_arguments") or [])[:4])
        missing = card.get("missing_evidence") or []
        if missing:
            risks.append(f"Missing evidence remains: {len(missing)} item(s).")
        if card.get("freshness_status") != "fresh":
            risks.append(f"Freshness status is {card.get('freshness_status')}.")
        if card.get("ai_compression_refs"):
            risks.append("AI compression is context only and must be checked against evidence.")
        return list(dict.fromkeys(risks))

    def _next_action(self, decision: str, card: dict[str, Any], validation: dict[str, Any]) -> str:
        if validation.get("status") == "failed":
            return "Repair validation findings before this card is used in any brief."
        if decision == "active-watch":
            return "Keep in active research watch and refresh evidence on the next scheduled source run."
        if decision == "needs-followup":
            return "Run queued follow-up jobs, then rebuild and validate the research card."
        if decision == "manual-review":
            return "Perform manual review before promotion or reuse."
        if decision == "archive-background":
            return "Keep as background context; do not use as current primary evidence."
        return "Review workflow state before reuse."

    def _policy_gate(self, brief: dict[str, Any]) -> dict[str, Any]:
        fragments: list[str] = []
        for action in brief.get("operator_actions") or []:
            fragments.append(str(action.get("detail") or ""))
        for item in brief.get("watch_items") or []:
            fragments.append(str(item.get("headline") or ""))
            fragments.append(str(item.get("next_action") or ""))
            fragments.extend(str(value) for value in item.get("research_risks") or [])
        text = "\n".join(fragments).lower()
        hits = sorted({pattern for pattern in FORBIDDEN_ACTION_PATTERNS if re.search(pattern, text)})
        return {
            "status": "passed" if not hits else "blocked",
            "forbidden_hits": hits,
            "rules": [
                "P4 briefs summarize research operations only.",
                "P4 briefs must not contain trading actions.",
                "P4 briefs must preserve validation and follow-up boundaries.",
            ],
        }

    def _watch_item_id(self, time_window: str, decision: dict[str, Any], card: dict[str, Any], pipeline_run_id: str | None) -> str:
        if pipeline_run_id:
            value = f"phase4-watch:{pipeline_run_id}:{time_window}:{decision.get('decision_id')}:{card.get('card_id')}"
        else:
            value = f"phase4-watch:{time_window}:{decision.get('decision_id')}:{card.get('card_id')}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def _brief_id(self, time_window: str, created_at: str, watch_items: list[dict[str, Any]], pipeline_run_id: str | None) -> str:
        ids = ",".join(item["watch_item_id"] for item in watch_items)
        if pipeline_run_id:
            value = f"phase4-brief:{pipeline_run_id}:{time_window}:{ids}"
        else:
            value = f"phase4-brief:{time_window}:{created_at}:{ids}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
