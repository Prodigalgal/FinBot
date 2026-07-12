from __future__ import annotations

from typing import Any


class InformationCompressionPlanner:
    def build(self, documents, events: list[dict[str, Any]]) -> dict[str, Any]:
        candidates = [self._document_candidate(row) for row in documents if self._needs_compression(row)]
        event_candidates = [self._event_candidate(event) for event in events if self._event_needs_compression(event)]
        return {
            "policy": "deterministic-extraction-first-ai-compression-second",
            "ai_assisted_compression_recommended": bool(candidates or event_candidates),
            "rules": [
                "Raw evidence and normalized documents remain the source of truth.",
                "AI compression output must keep evidence_id/document_id citations.",
                "AI compression may summarize and rank context, but must not invent facts.",
                "Structured facts, market snapshots, and source health are injected before AI compression.",
            ],
            "document_candidates": candidates[:50],
            "event_candidates": event_candidates[:50],
        }

    def _needs_compression(self, row) -> bool:
        text = row["text"] or ""
        return len(text) > 3000 or row["category"] in {"broad_market_news", "crypto_news", "energy_news", "macro_market_news"}

    def _event_needs_compression(self, event: dict[str, Any]) -> bool:
        readiness = event.get("research_readiness")
        document_count = int((event.get("metadata") or {}).get("document_count") or 0)
        return readiness in {"research-ready", "needs-corroboration"} or document_count >= 3

    def _document_candidate(self, row) -> dict[str, Any]:
        text = row["text"] or ""
        return {
            "document_id": row["document_id"],
            "evidence_id": row["evidence_id"],
            "source_id": row["source_id"],
            "category": row["category"],
            "title": row["title"],
            "text_length": len(text),
            "extractive_preview": self._preview(text),
            "recommended_mode": "ai-compress-with-citations" if len(text) > 3000 else "deterministic-preview-ok",
        }

    def _event_candidate(self, event: dict[str, Any]) -> dict[str, Any]:
        return {
            "event_id": event["event_id"],
            "event_key": event["event_key"],
            "title": event["title"],
            "research_readiness": event.get("research_readiness"),
            "document_ids": event.get("document_ids", []),
            "recommended_mode": "cluster-summary-with-evidence-citations",
        }

    def _preview(self, text: str) -> str:
        value = " ".join(text.split())
        return value[:700]
