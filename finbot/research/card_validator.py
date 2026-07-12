from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from typing import Any

from finbot.research.research_cards import POLICY_FLAGS
from finbot.storage.sqlite_store import SQLiteStore


REQUIRED_TOP_LEVEL_FIELDS = {
    "card_id",
    "version",
    "time_window",
    "created_at",
    "event_id",
    "event_key",
    "readiness",
    "freshness_status",
    "headline",
    "summary",
    "freshness",
    "fresh_evidence",
    "stale_context",
    "discarded_stale_refs",
    "evidence_assessment",
    "market_context",
    "ai_compression_refs",
    "counter_arguments",
    "missing_evidence",
    "follow_up_jobs",
    "source_refs",
    "policy_flags",
    "policy_gate",
}

REQUIRED_POLICY_FLAGS = set(POLICY_FLAGS)
FORBIDDEN_TRADING_PATTERNS = (
    r"\bbuy\b",
    r"\bsell\b",
    r"\bgo long\b",
    r"\bgo short\b",
    r"\blong position\b",
    r"\bshort position\b",
    r"\btarget price\b",
    r"\bstop loss\b",
    r"\btake profit\b",
    r"\bentry price\b",
    r"\bexit price\b",
)


class ResearchCardValidator:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def validate_all(
        self,
        limit: int | None = None,
        clear_existing: bool = False,
        pipeline_run_id: str | None = None,
        input_pipeline_run_id: str | None = None,
        idempotent_outputs: bool = True,
    ) -> dict[str, Any]:
        if clear_existing:
            self.store.clear_research_card_validations()
        elif pipeline_run_id and idempotent_outputs:
            self.store.clear_research_card_validations(pipeline_run_id=pipeline_run_id)
        indexes = self._indexes()
        rows = self.store.list_research_cards(limit=limit, pipeline_run_id=input_pipeline_run_id)
        validations = [self.validate_row(row, indexes, pipeline_run_id=pipeline_run_id) for row in rows]
        for validation in validations:
            self.store.insert_research_card_validation(validation)
        return {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "total_cards": len(validations),
            "statuses": dict(Counter(validation["status"] for validation in validations)),
            "finding_counts": dict(Counter(finding["code"] for validation in validations for finding in validation["findings"])),
            "validations": validations,
        }

    def validate_row(self, row: Any, indexes: dict[str, set[str]], pipeline_run_id: str | None = None) -> dict[str, Any]:
        card = _loads(row["payload_json"], {})
        findings: list[dict[str, Any]] = []
        self._validate_required_fields(card, findings)
        self._validate_event_reference(card, indexes, findings)
        self._validate_policy(card, findings)
        self._validate_forbidden_terms(card, findings)
        self._validate_freshness(card, findings)
        self._validate_source_refs(card, indexes, findings)
        self._validate_ai_compressions(card, indexes, findings)
        self._validate_readiness_context(card, findings)

        error_count = sum(1 for finding in findings if finding["severity"] == "error")
        warning_count = sum(1 for finding in findings if finding["severity"] == "warning")
        status = "failed" if error_count else "warning" if warning_count else "passed"
        score = max(0.0, 100.0 - error_count * 20.0 - warning_count * 5.0)
        created_at = datetime.now(timezone.utc).isoformat()
        validation = {
            "validation_id": self._validation_id(card, created_at, pipeline_run_id),
            "pipeline_run_id": pipeline_run_id,
            "card_id": card.get("card_id") or row["card_id"],
            "event_id": card.get("event_id") or row["event_id"],
            "status": status,
            "score": round(score, 2),
            "error_count": error_count,
            "warning_count": warning_count,
            "findings": findings,
            "created_at": created_at,
        }
        return validation

    def _indexes(self) -> dict[str, set[str]]:
        documents = self.store.list_normalized_documents(limit=None)
        evidence = self.store.list_raw_evidence(only_success=False, limit=None)
        compressions = self.store.list_ai_compressions(limit=None)
        events = self.store.list_event_candidates(limit=None)
        return {
            "document_ids": {row["document_id"] for row in documents},
            "evidence_ids": {row["evidence_id"] for row in evidence},
            "source_ids": {row["id"] for row in self.store.source_map().values()},
            "compression_ids": {row["compression_id"] for row in compressions},
            "event_ids": {row["event_id"] for row in events},
        }

    def _validate_required_fields(self, card: dict[str, Any], findings: list[dict[str, Any]]) -> None:
        missing = sorted(field for field in REQUIRED_TOP_LEVEL_FIELDS if field not in card)
        if missing:
            findings.append(_finding("error", "missing_required_fields", f"Missing fields: {', '.join(missing)}"))

    def _validate_event_reference(self, card: dict[str, Any], indexes: dict[str, set[str]], findings: list[dict[str, Any]]) -> None:
        event_id = card.get("event_id")
        if event_id and event_id not in indexes["event_ids"]:
            findings.append(_finding("error", "missing_event_reference", f"event_id not found in event_candidates: {event_id}"))

    def _validate_policy(self, card: dict[str, Any], findings: list[dict[str, Any]]) -> None:
        policy_flags = set(card.get("policy_flags") or [])
        missing_flags = sorted(REQUIRED_POLICY_FLAGS - policy_flags)
        if missing_flags:
            findings.append(_finding("error", "missing_policy_flags", f"Missing policy flags: {', '.join(missing_flags)}"))
        policy_gate = card.get("policy_gate") or {}
        if policy_gate.get("status") != "passed":
            findings.append(_finding("error", "policy_gate_not_passed", f"Policy gate status: {policy_gate.get('status')}"))

    def _validate_forbidden_terms(self, card: dict[str, Any], findings: list[dict[str, Any]]) -> None:
        text = " ".join(
            [
                str(card.get("headline") or ""),
                str(card.get("summary") or ""),
                " ".join(str(value) for value in card.get("counter_arguments") or []),
            ]
        ).lower()
        hits = sorted({pattern for pattern in FORBIDDEN_TRADING_PATTERNS if re.search(pattern, text)})
        if hits:
            findings.append(_finding("error", "forbidden_trading_language", f"Forbidden trading language matched: {', '.join(hits)}"))

    def _validate_freshness(self, card: dict[str, Any], findings: list[dict[str, Any]]) -> None:
        freshness_status = card.get("freshness_status")
        fresh_evidence = card.get("fresh_evidence") or []
        stale_context = card.get("stale_context") or []
        discarded = card.get("discarded_stale_refs") or []

        if freshness_status == "fresh" and not fresh_evidence:
            findings.append(_finding("error", "fresh_status_without_fresh_evidence", "freshness_status is fresh but fresh_evidence is empty"))
        if freshness_status == "no-current-evidence" and card.get("readiness") == "research-ready":
            findings.append(_finding("error", "research_ready_without_current_evidence", "research-ready card has no current evidence"))
        if freshness_status == "stale-context-only":
            findings.append(_finding("warning", "stale_context_only", "Card has only stale context and should not be promoted without refresh"))
        if discarded and not fresh_evidence:
            findings.append(_finding("warning", "discarded_stale_without_fresh_evidence", "Old items were discarded and no fresh evidence remains"))

        for ref in fresh_evidence:
            status = ((ref.get("freshness") or {}).get("status"))
            if status != "fresh":
                findings.append(_finding("error", "fresh_evidence_bad_status", f"fresh_evidence item has freshness status {status}"))
        for ref in stale_context:
            status = ((ref.get("freshness") or {}).get("status"))
            if status != "stale-context":
                findings.append(_finding("error", "stale_context_bad_status", f"stale_context item has freshness status {status}"))
        for ref in discarded:
            status = ((ref.get("freshness") or {}).get("status"))
            if status != "discarded-stale":
                findings.append(_finding("error", "discarded_stale_bad_status", f"discarded item has freshness status {status}"))

    def _validate_source_refs(self, card: dict[str, Any], indexes: dict[str, set[str]], findings: list[dict[str, Any]]) -> None:
        refs = card.get("source_refs") or []
        fresh_evidence = card.get("fresh_evidence") or []
        if fresh_evidence and not refs:
            findings.append(_finding("error", "fresh_evidence_without_source_refs", "fresh_evidence exists but source_refs is empty"))

        for ref in refs:
            document_id = ref.get("document_id")
            evidence_id = ref.get("evidence_id")
            source_id = ref.get("source_id")
            if document_id and document_id not in indexes["document_ids"]:
                findings.append(_finding("error", "missing_document_reference", f"document_id not found: {document_id}"))
            if evidence_id and evidence_id not in indexes["evidence_ids"]:
                findings.append(_finding("error", "missing_evidence_reference", f"evidence_id not found: {evidence_id}"))
            if source_id and source_id not in indexes["source_ids"]:
                findings.append(_finding("error", "missing_source_reference", f"source_id not found: {source_id}"))

    def _validate_ai_compressions(self, card: dict[str, Any], indexes: dict[str, set[str]], findings: list[dict[str, Any]]) -> None:
        for ref in card.get("ai_compression_refs") or []:
            compression_id = ref.get("compression_id")
            if compression_id and compression_id not in indexes["compression_ids"]:
                findings.append(_finding("error", "missing_ai_compression_reference", f"compression_id not found: {compression_id}"))
            if ref.get("trust_policy") != "context-compression-only":
                findings.append(_finding("error", "invalid_ai_compression_trust_policy", "AI compression ref must be context-compression-only"))
            if ref.get("status") != "completed":
                findings.append(_finding("warning", "ai_compression_not_completed", f"AI compression status is {ref.get('status')}"))

    def _validate_readiness_context(self, card: dict[str, Any], findings: list[dict[str, Any]]) -> None:
        readiness = card.get("readiness")
        missing_evidence = card.get("missing_evidence") or []
        market_context = card.get("market_context") or []
        evidence_assessment = card.get("evidence_assessment") or {}
        review_flags = set(evidence_assessment.get("review_flags") or [])

        if readiness == "research-ready" and missing_evidence:
            findings.append(_finding("error", "research_ready_has_missing_evidence", "research-ready card still lists missing evidence"))
        if "missing_market_confirmation" in review_flags and not market_context:
            findings.append(_finding("warning", "missing_market_context", "Card review flags mention missing market confirmation and no market context is attached"))
        if readiness == "needs-corroboration" and not missing_evidence and not review_flags:
            findings.append(_finding("warning", "needs_corroboration_without_reason", "needs-corroboration card has no missing_evidence or review_flags"))

    def _validation_id(self, card: dict[str, Any], created_at: str, pipeline_run_id: str | None) -> str:
        if pipeline_run_id:
            value = f"{pipeline_run_id}:{card.get('card_id')}:{card.get('event_id')}:validator-v1:{json.dumps(card, ensure_ascii=False, sort_keys=True, default=str)}"
        else:
            value = f"{card.get('card_id')}:{card.get('event_id')}:{created_at}:{json.dumps(card, ensure_ascii=False, sort_keys=True, default=str)}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _finding(severity: str, code: str, message: str) -> dict[str, str]:
    return {
        "severity": severity,
        "code": code,
        "message": message,
    }


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
