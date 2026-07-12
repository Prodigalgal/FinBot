from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable

from finbot.ai.openai_compatible import (
    LLMCompletion,
    OpenAICompatibleClient,
    OpenAICompatibleError,
    OpenAICompatibleProvider,
    provider_status,
)
from finbot.config.ai_sites import DEFAULT_COMPRESSION_SYSTEM_PROMPT, render_prompt_template
from finbot.research.compression import InformationCompressionPlanner
from finbot.research.readiness_gate import ResearchReadinessGate
from finbot.storage.sqlite_store import SQLiteStore


COMPRESSION_SYSTEM_PROMPT = DEFAULT_COMPRESSION_SYSTEM_PROMPT


@dataclass(frozen=True)
class AICompressionRunConfig:
    pipeline_run_id: str | None = None
    protocol: str = "chat"
    reasoning_effort: str = "provider_default"
    provider_order: tuple[str, ...] = ("deepseek", "mimo")
    limit_documents: int = 5
    limit_events: int = 3
    source_document_limit: int = 200
    source_event_limit: int = 100
    max_document_chars: int = 9000
    max_event_chars: int = 16000
    dry_run: bool = False
    clear_existing: bool = False
    idempotent_outputs: bool = True
    system_prompt: str = COMPRESSION_SYSTEM_PROMPT
    user_prompt_template: str = "{payload_json}"


class AICompressionRunner:
    def __init__(
        self,
        store: SQLiteStore,
        providers: dict[str, OpenAICompatibleProvider],
        client: OpenAICompatibleClient | None = None,
    ):
        self.store = store
        self.providers = providers
        self.client = client or OpenAICompatibleClient()

    def run(self, config: AICompressionRunConfig) -> dict[str, Any]:
        documents = self.store.list_normalized_documents(limit=config.source_document_limit)
        document_map = {row["document_id"]: row for row in documents}
        event_rows = [_event_row(row) for row in self.store.list_event_candidates(limit=config.source_event_limit)]
        annotated_events = ResearchReadinessGate().annotate(event_rows)
        plan = InformationCompressionPlanner().build(documents, annotated_events)
        document_candidates = plan["document_candidates"][: max(config.limit_documents, 0)]
        event_candidates = plan["event_candidates"][: max(config.limit_events, 0)]
        statuses = [
            provider_status(provider, config.protocol).__dict__
            for provider in self._providers_in_order(config.provider_order)
        ]
        report: dict[str, Any] = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "protocol": config.protocol,
            "reasoning_effort": config.reasoning_effort,
            "dry_run": config.dry_run,
            "provider_status": statuses,
            "candidate_counts": {
                "documents": len(document_candidates),
                "events": len(event_candidates),
            },
            "results": [],
        }
        if config.dry_run:
            report["document_candidates"] = document_candidates
            report["event_candidates"] = event_candidates
            return report

        if config.clear_existing:
            self.store.clear_ai_compressions()
        elif config.pipeline_run_id and config.idempotent_outputs:
            self.store.clear_ai_compressions(pipeline_run_id=config.pipeline_run_id)

        for candidate in document_candidates:
            target = self._build_document_target(candidate, document_map, config.max_document_chars)
            report["results"].append(self._compress_target(target, config))
        for candidate in event_candidates:
            target = self._build_event_target(candidate, document_map, config.max_event_chars)
            report["results"].append(self._compress_target(target, config))
        report["summary"] = _summarize_results(report["results"])
        return report

    def _compress_target(self, target: dict[str, Any], config: AICompressionRunConfig) -> dict[str, Any]:
        payload_json = json.dumps(target["prompt_payload"], ensure_ascii=False, default=str)
        user_prompt = render_prompt_template(
            config.user_prompt_template or "{payload_json}",
            payload_json=payload_json,
            target_type=str(target["target_type"]),
            target_id=str(target["target_id"]),
        )
        system_prompt = config.system_prompt or COMPRESSION_SYSTEM_PROMPT
        prompt_hash = _hash_prompt(system_prompt, user_prompt)
        attempts: list[dict[str, Any]] = []
        for provider in self._providers_in_order(config.provider_order):
            status = provider_status(provider, config.protocol)
            if not status.configured:
                attempts.append(
                    {
                        "provider": provider.name,
                        "status": "skipped",
                        "missing": status.missing,
                    }
                )
                continue
            try:
                completion = self.client.complete(
                    provider=provider,
                    protocol=config.protocol,
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    require_json=True,
                    reasoning_effort=config.reasoning_effort,
                )
                summary = _parse_summary_json(completion)
                record = _compression_record(
                    target=target,
                    completion=completion,
                    prompt_hash=prompt_hash,
                    summary=summary,
                    status="completed",
                    error=None,
                    pipeline_run_id=config.pipeline_run_id,
                )
                self.store.insert_ai_compression(record)
                return {
                    "target_type": target["target_type"],
                    "target_id": target["target_id"],
                    "provider": completion.provider,
                    "protocol": completion.protocol,
                    "status": "completed",
                    "compression_id": record["compression_id"],
                }
            except (OpenAICompatibleError, ValueError) as exc:
                attempts.append(
                    {
                        "provider": provider.name,
                        "status": "failed",
                        "error": _redact_error(str(exc)),
                    }
                )

        failed_record = _failed_compression_record(
            target=target,
            protocol=config.protocol,
            provider=_attempted_provider_name(attempts),
            prompt_hash=prompt_hash,
            attempts=attempts,
            pipeline_run_id=config.pipeline_run_id,
        )
        self.store.insert_ai_compression(failed_record)
        return {
            "target_type": target["target_type"],
            "target_id": target["target_id"],
            "provider": failed_record["provider"],
            "protocol": config.protocol,
            "status": "failed",
            "compression_id": failed_record["compression_id"],
            "attempts": attempts,
        }

    def _build_document_target(self, candidate: dict[str, Any], document_map: dict[str, Any], max_chars: int) -> dict[str, Any]:
        row = document_map[candidate["document_id"]]
        source_ref = _document_source_ref(row)
        return {
            "target_type": "document",
            "target_id": candidate["document_id"],
            "source_refs": [source_ref],
            "prompt_payload": {
                "task": "compress_single_document",
                "target_type": "document",
                "document": {
                    **source_ref,
                    "category": row["category"],
                    "tier": row["tier"],
                    "trust_weight": row["trust_weight"],
                    "title": row["title"],
                    "fetched_at": row["fetched_at"],
                    "text": _truncate_text(row["text"] or "", max_chars),
                },
            },
        }

    def _build_event_target(self, candidate: dict[str, Any], document_map: dict[str, Any], max_chars: int) -> dict[str, Any]:
        document_ids = candidate.get("document_ids") or []
        rows = [document_map[document_id] for document_id in document_ids if document_id in document_map]
        source_refs = [_document_source_ref(row, event_id=candidate["event_id"]) for row in rows]
        per_document_chars = max(1200, max_chars // max(len(rows), 1))
        documents = [
            {
                **_document_source_ref(row, event_id=candidate["event_id"]),
                "category": row["category"],
                "tier": row["tier"],
                "trust_weight": row["trust_weight"],
                "title": row["title"],
                "fetched_at": row["fetched_at"],
                "text": _truncate_text(row["text"] or "", per_document_chars),
            }
            for row in rows
        ]
        return {
            "target_type": "event",
            "target_id": candidate["event_id"],
            "source_refs": source_refs,
            "prompt_payload": {
                "task": "compress_event_cluster",
                "target_type": "event",
                "event": {
                    "event_id": candidate["event_id"],
                    "event_key": candidate["event_key"],
                    "title": candidate["title"],
                    "research_readiness": candidate.get("research_readiness"),
                    "document_ids": document_ids,
                },
                "documents": documents,
            },
        }

    def _providers_in_order(self, provider_order: Iterable[str]) -> list[OpenAICompatibleProvider]:
        providers: list[OpenAICompatibleProvider] = []
        for name in provider_order:
            provider = self.providers.get(name)
            if provider is not None:
                providers.append(provider)
        return providers


def _event_row(row) -> dict[str, Any]:
    metadata = _loads(row["metadata_json"], {})
    return {
        "event_id": row["event_id"],
        "event_key": row["event_key"],
        "title": row["title"],
        "category": row["category"],
        "asset_scope": _loads(row["asset_scope_json"], []),
        "document_ids": _loads(row["document_ids_json"], []),
        "source_ids": _loads(row["source_ids_json"], []),
        "confidence": row["confidence"],
        "first_seen_at": row["first_seen_at"],
        "last_seen_at": row["last_seen_at"],
        "summary": row["summary"],
        "metadata": metadata,
        "priority": metadata.get("priority"),
        "confirmation_state": metadata.get("confirmation_state"),
        "quality": {
            "score": metadata.get("quality_score"),
            "review_flags": metadata.get("review_flags", []),
            "conflict_flags": metadata.get("conflict_flags", []),
            "suggested_followups": metadata.get("suggested_followups", []),
        },
    }


def _document_source_ref(row, event_id: str | None = None) -> dict[str, Any]:
    ref = {
        "document_id": row["document_id"],
        "evidence_id": row["evidence_id"],
        "source_id": row["source_id"],
        "canonical_url": row["canonical_url"],
    }
    if event_id is not None:
        ref["event_id"] = event_id
    return ref


def _compression_record(
    target: dict[str, Any],
    completion: LLMCompletion,
    prompt_hash: str,
    summary: dict[str, Any],
    status: str,
    error: str | None,
    pipeline_run_id: str | None,
) -> dict[str, Any]:
    compression_id = _compression_id(
        target["target_type"],
        target["target_id"],
        completion.provider,
        completion.protocol,
        prompt_hash,
    )
    return {
        "compression_id": compression_id,
        "pipeline_run_id": pipeline_run_id,
        "target_type": target["target_type"],
        "target_id": target["target_id"],
        "provider": completion.provider,
        "protocol": completion.protocol,
        "model": completion.model,
        "status": status,
        "summary": {**summary, "usage": completion.usage},
        "prompt_hash": prompt_hash,
        "source_refs": target["source_refs"],
        "error": error,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }


def _failed_compression_record(
    target: dict[str, Any],
    protocol: str,
    provider: str,
    prompt_hash: str,
    attempts: list[dict[str, Any]],
    pipeline_run_id: str | None,
) -> dict[str, Any]:
    return {
        "compression_id": _compression_id(target["target_type"], target["target_id"], provider, protocol, prompt_hash),
        "pipeline_run_id": pipeline_run_id,
        "target_type": target["target_type"],
        "target_id": target["target_id"],
        "provider": provider,
        "protocol": protocol,
        "model": None,
        "status": "failed",
        "summary": {"attempts": attempts},
        "prompt_hash": prompt_hash,
        "source_refs": target["source_refs"],
        "error": "; ".join(_attempt_error(attempt) for attempt in attempts if attempt.get("error"))[:1000] or "no configured provider",
        "created_at": datetime.now(timezone.utc).isoformat(),
    }


def _parse_summary_json(completion: LLMCompletion) -> dict[str, Any]:
    content = _strip_code_fence(completion.content)
    try:
        value = json.loads(content)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Provider {completion.provider} returned invalid compression JSON") from exc
    if not isinstance(value, dict):
        raise ValueError(f"Provider {completion.provider} returned non-object compression JSON")
    for key, fallback in {
        "summary": "",
        "key_points": [],
        "risks": [],
        "missing_evidence": [],
        "citations": [],
    }.items():
        value.setdefault(key, fallback)
    return value


def _strip_code_fence(content: str) -> str:
    value = content.strip()
    if value.startswith("```"):
        lines = value.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        value = "\n".join(lines).strip()
    return value


def _hash_prompt(system_prompt: str, user_prompt: str) -> str:
    return hashlib.sha256(f"{system_prompt}\n{user_prompt}".encode("utf-8")).hexdigest()


def _compression_id(target_type: str, target_id: str, provider: str, protocol: str, prompt_hash: str) -> str:
    value = f"{target_type}:{target_id}:{provider}:{protocol}:{prompt_hash}"
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _truncate_text(text: str, max_chars: int) -> str:
    cleaned = " ".join(text.split())
    if len(cleaned) <= max_chars:
        return cleaned
    return f"{cleaned[:max_chars]} [truncated]"


def _summarize_results(results: list[dict[str, Any]]) -> dict[str, int]:
    summary: dict[str, int] = {}
    for result in results:
        status = result["status"]
        summary[status] = summary.get(status, 0) + 1
    return dict(sorted(summary.items()))


def _attempted_provider_name(attempts: list[dict[str, Any]]) -> str:
    failed = [attempt["provider"] for attempt in attempts if attempt.get("status") == "failed"]
    skipped = [attempt["provider"] for attempt in attempts if attempt.get("status") == "skipped"]
    return (failed or skipped or ["none"])[-1]


def _attempt_error(attempt: dict[str, Any]) -> str:
    return f"{attempt.get('provider')}: {attempt.get('error')}"


def _redact_error(value: str) -> str:
    return value.replace("\n", " ")[:500]


def _loads(value: str | None, default):
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
