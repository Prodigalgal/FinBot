from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.research.context_retriever import ResearchContextPack


POLICY_FLAGS = [
    "research_only",
    "no_trading",
    "no_order_execution",
    "not_investment_advice",
    "ai_compression_is_not_fact_source",
    "freshness_first",
]


@dataclass(frozen=True)
class ResearchCardBuildConfig:
    time_window: str = "phase3-latest"
    version: str = "phase3-research-card-v1"
    pipeline_run_id: str | None = None


class ResearchCardBuilder:
    def build(self, context: ResearchContextPack, config: ResearchCardBuildConfig) -> dict[str, Any]:
        event = context.event
        freshness_status = self._freshness_status(context)
        card = {
            "card_id": self._card_id(event["event_id"], config.time_window, config.version),
            "pipeline_run_id": config.pipeline_run_id,
            "version": config.version,
            "time_window": config.time_window,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "event_id": event["event_id"],
            "event_key": event["event_key"],
            "readiness": event.get("research_readiness"),
            "priority": event.get("priority"),
            "freshness_status": freshness_status,
            "headline": event.get("title"),
            "summary": self._summary(context),
            "event": event,
            "freshness": {
                "status": freshness_status,
                "policy": context.freshness_policy,
                "fresh_evidence_count": len(context.fresh_evidence),
                "stale_context_count": len(context.stale_context),
                "discarded_stale_count": len(context.discarded_stale_refs),
            },
            "fresh_evidence": context.fresh_evidence,
            "stale_context": context.stale_context,
            "discarded_stale_refs": context.discarded_stale_refs,
            "evidence_assessment": self._evidence_assessment(context),
            "macro_context": context.macro_context,
            "market_context": context.market_context,
            "ai_compression_refs": self._ai_compression_refs(context),
            "impact_channels": self._impact_channels(event),
            "counter_arguments": self._counter_arguments(context),
            "missing_evidence": context.missing_context,
            "follow_up_jobs": self._follow_up_jobs(context),
            "source_refs": self._source_refs(context),
            "policy_flags": POLICY_FLAGS,
        }
        card["policy_gate"] = self._policy_gate(card)
        return card

    def _summary(self, context: ResearchContextPack) -> str:
        event = context.event
        if context.ai_compressions:
            for compression in context.ai_compressions:
                if compression.get("status") == "completed":
                    summary = (compression.get("summary") or {}).get("summary")
                    if summary:
                        return str(summary)
        if event.get("summary"):
            return str(event["summary"])
        return f"{event.get('title')} ({event.get('research_readiness')})"

    def _evidence_assessment(self, context: ResearchContextPack) -> dict[str, Any]:
        quality = context.event.get("quality") or {}
        return {
            "quality_score": quality.get("score"),
            "confirmation_state": context.event.get("confirmation_state"),
            "evidence_tiers": quality.get("evidence_tiers", {}),
            "source_categories": quality.get("source_categories", {}),
            "average_trust_weight": quality.get("average_trust_weight"),
            "review_flags": quality.get("review_flags", []),
            "conflict_flags": quality.get("conflict_flags", []),
            "fresh_evidence_count": len(context.fresh_evidence),
            "source_count": len(context.event.get("source_ids") or []),
            "document_count": len(context.event.get("document_ids") or []),
        }

    def _ai_compression_refs(self, context: ResearchContextPack) -> list[dict[str, Any]]:
        refs: list[dict[str, Any]] = []
        for compression in context.ai_compressions:
            refs.append(
                {
                    "compression_id": compression["compression_id"],
                    "target_type": compression["target_type"],
                    "target_id": compression["target_id"],
                    "provider": compression["provider"],
                    "protocol": compression["protocol"],
                    "model": compression["model"],
                    "status": compression["status"],
                    "summary": compression.get("summary"),
                    "created_at": compression["created_at"],
                    "trust_policy": "context-compression-only",
                }
            )
        return refs

    def _impact_channels(self, event: dict[str, Any]) -> list[dict[str, Any]]:
        channels: list[dict[str, Any]] = []
        for asset in event.get("asset_scope") or []:
            channels.append(
                {
                    "asset": asset,
                    "channel": _channel_for_asset(asset),
                    "claim_policy": "hypothesis-only-no-trading-action",
                }
            )
        return channels

    def _counter_arguments(self, context: ResearchContextPack) -> list[str]:
        quality = context.event.get("quality") or {}
        arguments = [f"Review flag present: {flag}" for flag in quality.get("review_flags", [])]
        arguments.extend(f"Conflict flag present: {flag}" for flag in quality.get("conflict_flags", []))
        if context.stale_context:
            arguments.append("Some supporting material is stale and should be treated as background only.")
        if context.discarded_stale_refs:
            arguments.append("Some old items were excluded because ordinary stale news has weak current market impact.")
        if not context.market_context:
            arguments.append("No matching market context snapshot is attached to this card.")
        if context.ai_compressions:
            arguments.append("AI compression is a summarization aid and must be checked against cited evidence.")
        return arguments

    def _follow_up_jobs(self, context: ResearchContextPack) -> list[dict[str, Any]]:
        followups: list[str] = list((context.event.get("quality") or {}).get("suggested_followups") or [])
        followups.extend(context.missing_context)
        if context.discarded_stale_refs:
            followups.append("Refresh this topic with current evidence before promoting it to research-ready.")
        unique_followups = []
        for followup in followups:
            if followup and followup not in unique_followups:
                unique_followups.append(followup)
        return [
            {
                "job_type": "research-follow-up",
                "priority": context.event.get("priority") or "P3",
                "detail": followup,
                "event_id": context.event["event_id"],
            }
            for followup in unique_followups
        ]

    def _source_refs(self, context: ResearchContextPack) -> list[dict[str, Any]]:
        refs: list[dict[str, Any]] = []
        for section, rows in (
            ("fresh_evidence", context.fresh_evidence),
            ("stale_context", context.stale_context),
            ("discarded_stale", context.discarded_stale_refs),
        ):
            for row in rows:
                refs.append(
                    {
                        "section": section,
                        "document_id": row.get("document_id"),
                        "evidence_id": row.get("evidence_id"),
                        "source_id": row.get("source_id"),
                        "canonical_url": row.get("canonical_url"),
                    }
                )
        return refs

    def _freshness_status(self, context: ResearchContextPack) -> str:
        if context.fresh_evidence:
            return "fresh"
        if context.stale_context:
            return "stale-context-only"
        return "no-current-evidence"

    def _policy_gate(self, card: dict[str, Any]) -> dict[str, Any]:
        text = " ".join(str(card.get(key) or "") for key in ("summary", "headline")).lower()
        forbidden_hits = [term for term in ("buy ", "sell ", "long ", "short ", "target price", "stop loss") if term in text]
        return {
            "status": "passed" if not forbidden_hits else "blocked",
            "forbidden_hits": forbidden_hits,
            "rules": [
                "Research cards must not contain trading actions.",
                "Research cards must preserve evidence citations.",
                "AI compression cannot override source evidence or freshness gates.",
            ],
        }

    def _card_id(self, event_id: str, time_window: str, version: str) -> str:
        return hashlib.sha256(f"{version}:{time_window}:{event_id}".encode("utf-8")).hexdigest()


def _channel_for_asset(asset: str) -> str:
    upper = asset.upper()
    if upper in {"DXY", "US10Y", "DGS10"}:
        return "rates-and-dollar"
    if upper in {"XAUUSD", "XAGUSD"}:
        return "precious-metals"
    if upper in {"BTCUSDT", "ETHUSDT"}:
        return "crypto-risk"
    if upper in {"NAS100", "SPX", "QQQ"}:
        return "equity-risk"
    if upper in {"XTIUSD", "USOIL", "BRENT"}:
        return "energy"
    return "cross-asset"
