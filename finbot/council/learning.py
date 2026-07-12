from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


COMPONENT_WEIGHTS = {
    "completion": 0.25,
    "evidence_coverage": 0.25,
    "chair_adoption": 0.20,
    "human_review": 0.15,
    "mature_outcome": 0.15,
}


class CouncilLearningService:
    def __init__(self, store: SQLiteStore) -> None:
        self.store = store
        self.store.init_schema()

    def refresh_debate(self, debate_id: str) -> dict[str, Any]:
        council = self.store.get_ai_debate_council(debate_id)
        if council is None:
            raise LookupError(f"未找到 Council 辩论：{debate_id}")
        messages = [_message_payload(row) for row in self.store.list_ai_debate_messages(debate_id=debate_id)]
        decisions = [dict(row) for row in self.store.list_ai_trade_decisions(debate_id=debate_id)]
        decision_ids = {str(item["decision_id"]) for item in decisions}
        reviews = [
            dict(row) for row in self.store.list_decision_reviews(limit=500)
            if str(row["decision_id"]) in decision_ids
        ]
        outcomes = [
            dict(row) for row in self.store.list_recommendation_outcomes(limit=5000)
            if str(row["decision_id"]) in decision_ids and row["status"] == "evaluated"
        ]
        audits = [dict(row) for row in self.store.list_claim_evidence_audits(debate_id=debate_id)]
        template_id = str(council["template_id"] or "product_advisory")
        role_messages: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for message in messages:
            if message.get("phase_id") == "chair_synthesis" or message.get("message_type") == "decision":
                continue
            role_id = str(message.get("agent_role") or "")
            if role_id:
                role_messages[role_id].append(message)
        chair_message = next(
            (
                message for message in messages
                if message.get("phase_id") == "chair_synthesis" or message.get("message_type") == "decision"
            ),
            {},
        )
        chair_refs = _message_evidence_refs(chair_message)
        scores = []
        for role_id, current_messages in sorted(role_messages.items()):
            score = self._role_score(
                debate_id=debate_id,
                template_id=template_id,
                role_id=role_id,
                messages=current_messages,
                audits=[item for item in audits if str(item.get("role_id") or "") == role_id],
                chair_refs=chair_refs,
                reviews=reviews,
                outcomes=outcomes,
                decisions=decisions,
            )
            self.store.upsert_council_role_score(score)
            scores.append(score)
        reflection = _reflection(
            debate_id=debate_id,
            scores=scores,
            messages=messages,
            audits=audits,
            chair_message=chair_message,
            reviews=reviews,
            outcomes=outcomes,
        )
        memories = self._store_memories(
            council=dict(council),
            scores=scores,
            reflection=reflection,
        )
        return {
            "status": "ok",
            "debate_id": debate_id,
            "template_id": template_id,
            "scores": scores,
            "reflection": reflection,
            "memories_created": memories,
            "policy": _learning_policy(),
        }

    def retrieve(
        self,
        *,
        template_id: str,
        role_id: str | None = None,
        product_id: str | None = None,
        symbol: str | None = None,
        market_type: str | None = None,
        topics: list[str] | tuple[str, ...] | None = None,
        limit: int = 5,
        max_chars: int = 6_000,
        mark_used: bool = True,
    ) -> dict[str, Any]:
        now = datetime.now(timezone.utc)
        topic_terms = _terms(" ".join(topics or []))
        candidates = []
        for row in self.store.list_council_memories(template_id=template_id, limit=1000):
            expires_at = _parse_datetime(row["expires_at"])
            if expires_at and expires_at <= now:
                continue
            if role_id and row["role_id"] and str(row["role_id"]) != role_id:
                continue
            if product_id and row["product_id"] and str(row["product_id"]) != product_id:
                continue
            if symbol and row["symbol"] and _normalize_symbol(str(row["symbol"])) != _normalize_symbol(symbol):
                continue
            if market_type and row["market_type"] and str(row["market_type"]).lower() != market_type.lower():
                continue
            row_topic_terms = _terms(str(row["topic"] or ""))
            topic_overlap = len(topic_terms.intersection(row_topic_terms))
            relevance = float(row["quality_score"] or 0.0)
            relevance += 3.0 if role_id and row["role_id"] == role_id else 0.5 if row["role_id"] is None else 0.0
            relevance += 3.0 if symbol and row["symbol"] and _normalize_symbol(str(row["symbol"])) == _normalize_symbol(symbol) else 0.0
            relevance += 2.0 if product_id and row["product_id"] == product_id else 0.0
            relevance += 1.0 if market_type and row["market_type"] == market_type else 0.0
            relevance += min(3.0, topic_overlap * 0.75)
            candidates.append((relevance, row))
        candidates.sort(key=lambda item: (item[0], item[1]["created_at"]), reverse=True)
        selected = []
        character_count = 0
        for relevance, row in candidates:
            item = {
                "memory_id": row["memory_id"],
                "memory_type": row["memory_type"],
                "role_id": row["role_id"],
                "product_id": row["product_id"],
                "symbol": row["symbol"],
                "market_type": row["market_type"],
                "topic": row["topic"],
                "content": _loads(row["content_json"], {}),
                "source_refs": _loads(row["source_refs_json"], []),
                "quality_score": row["quality_score"],
                "relevance_score": round(relevance, 4),
                "created_at": row["created_at"],
            }
            encoded_length = len(json.dumps(item, ensure_ascii=False, default=str))
            if selected and character_count + encoded_length > max(500, max_chars):
                break
            selected.append(item)
            character_count += encoded_length
            if len(selected) >= max(1, min(limit, 20)):
                break
        if mark_used:
            self.store.mark_council_memories_used(
                [str(item["memory_id"]) for item in selected],
                _now(),
            )
        return {
            "status": "ok",
            "count": len(selected),
            "items": selected,
            "character_count": character_count,
            "limits": {"max_items": max(1, min(limit, 20)), "max_chars": max(500, max_chars)},
            "policy": _learning_policy(),
        }

    def snapshot(self, *, template_id: str | None = None, limit: int = 100) -> dict[str, Any]:
        score_rows = self.store.list_council_role_scores(template_id=template_id, limit=5000)
        grouped: dict[tuple[str, str], list[Any]] = defaultdict(list)
        for row in score_rows:
            grouped[(str(row["template_id"]), str(row["role_id"]))].append(row)
        aggregates = []
        for (current_template_id, role_id), rows in sorted(grouped.items()):
            total_weight = sum(max(0.1, float(row["sample_weight"] or 0.0)) for row in rows)
            weighted_score = sum(
                float(row["score"]) * max(0.1, float(row["sample_weight"] or 0.0))
                for row in rows
            ) / total_weight
            aggregates.append(
                {
                    "template_id": current_template_id,
                    "role_id": role_id,
                    "score": round(weighted_score, 2),
                    "debate_count": len(rows),
                    "sample_weight": round(total_weight, 2),
                    "latest_at": max(str(row["created_at"]) for row in rows),
                }
            )
        memories = [
            _memory_payload(row)
            for row in self.store.list_council_memories(template_id=template_id, limit=limit)
        ]
        return {
            "status": "ok",
            "role_scores": aggregates,
            "memory_count": len(memories),
            "memories": memories,
            "policy": _learning_policy(),
        }

    def _role_score(
        self,
        *,
        debate_id: str,
        template_id: str,
        role_id: str,
        messages: list[dict[str, Any]],
        audits: list[dict[str, Any]],
        chair_refs: set[str],
        reviews: list[dict[str, Any]],
        outcomes: list[dict[str, Any]],
        decisions: list[dict[str, Any]],
    ) -> dict[str, Any]:
        completed_messages = [message for message in messages if message.get("status") == "completed"]
        completion = len(completed_messages) / len(messages) if messages else 0.0
        role_refs = set().union(*(_message_evidence_refs(message) for message in completed_messages)) if completed_messages else set()
        if audits:
            evidence_coverage = sum(bool(item.get("covered")) for item in audits) / len(audits)
        elif completed_messages:
            evidence_coverage = sum(bool(_message_evidence_refs(message)) for message in completed_messages) / len(completed_messages)
        else:
            evidence_coverage = 0.0
        chair_adoption = None
        if chair_refs:
            chair_adoption = len(role_refs.intersection(chair_refs)) / max(1, len(role_refs)) if role_refs else 0.0
        human_review = _review_component(reviews)
        mature_outcome = _outcome_component(outcomes)
        components = {
            "completion": round(completion, 4),
            "evidence_coverage": round(evidence_coverage, 4),
            "chair_adoption": _rounded(chair_adoption),
            "human_review": _rounded(human_review),
            "mature_outcome": _rounded(mature_outcome),
            "message_count": len(messages),
            "completed_message_count": len(completed_messages),
            "claim_audit_count": len(audits),
            "evidence_ref_count": len(role_refs),
        }
        available_components = {
            key: value for key, value in components.items()
            if key in COMPONENT_WEIGHTS and value is not None
        }
        available_weight = sum(COMPONENT_WEIGHTS[key] for key in available_components)
        score = sum(float(value) * COMPONENT_WEIGHTS[key] for key, value in available_components.items())
        score = score / available_weight * 100.0 if available_weight else 0.0
        source_refs = [
            *[f"message:{message['message_id']}" for message in messages if message.get("message_id")],
            *[f"audit:{item['audit_id']}" for item in audits if item.get("audit_id")],
            *[f"decision:{item['decision_id']}" for item in decisions],
            *[f"review:{item['review_id']}" for item in reviews],
            *[f"outcome:{item['outcome_id']}" for item in outcomes],
        ]
        return {
            "score_id": _stable_id("council-role-score", debate_id, role_id),
            "debate_id": debate_id,
            "template_id": template_id,
            "role_id": role_id,
            "score": round(score, 2),
            "sample_weight": max(1.0, len(messages) + len(outcomes)),
            "components": components,
            "source_refs": list(dict.fromkeys(source_refs)),
            "created_at": _now(),
        }

    def _store_memories(
        self,
        *,
        council: dict[str, Any],
        scores: list[dict[str, Any]],
        reflection: dict[str, Any],
    ) -> int:
        payload = _loads(council.get("payload_json"), {})
        candidates = payload.get("candidates") if isinstance(payload.get("candidates"), list) else []
        contexts = _memory_contexts(candidates) or [{}]
        template_id = str(council.get("template_id") or "product_advisory")
        debate_id = str(council["debate_id"])
        topic = " ".join(str(payload.get("user_query") or "").split()).strip()[:300] or None
        created_at = _now()
        expires_at = (datetime.now(timezone.utc) + timedelta(days=180)).isoformat()
        inserted = 0
        for context in contexts:
            reflection_memory = {
                "memory_id": _stable_id("council-memory", debate_id, context.get("symbol"), "reflection"),
                "template_id": template_id,
                "role_id": None,
                "product_id": context.get("product_id"),
                "symbol": context.get("symbol"),
                "market_type": context.get("market_type"),
                "topic": topic,
                "memory_type": "reflection",
                "content": reflection,
                "source_refs": reflection["source_refs"],
                "quality_score": _quality_from_scores(scores),
                "status": "active",
                "created_at": created_at,
                "expires_at": expires_at,
                "use_count": 0,
            }
            inserted += int(self.store.insert_council_memory_if_absent(reflection_memory))
            for score in scores:
                role_memory = {
                    **reflection_memory,
                    "memory_id": _stable_id(
                        "council-memory",
                        debate_id,
                        context.get("symbol"),
                        score["role_id"],
                    ),
                    "role_id": score["role_id"],
                    "memory_type": "role_lesson",
                    "content": {
                        "role_id": score["role_id"],
                        "score": score["score"],
                        "components": score["components"],
                        "effective_arguments": [
                            item for item in reflection["effective_arguments"]
                            if item.get("role_id") == score["role_id"]
                        ],
                        "missing_evidence": reflection["missing_evidence"][:10],
                        "automatic_change_allowed": False,
                    },
                    "source_refs": score["source_refs"],
                    "quality_score": float(score["score"]) / 100.0,
                }
                inserted += int(self.store.insert_council_memory_if_absent(role_memory))
        return inserted


def _reflection(
    *,
    debate_id: str,
    scores: list[dict[str, Any]],
    messages: list[dict[str, Any]],
    audits: list[dict[str, Any]],
    chair_message: dict[str, Any],
    reviews: list[dict[str, Any]],
    outcomes: list[dict[str, Any]],
) -> dict[str, Any]:
    chair_content = chair_message.get("content") if isinstance(chair_message.get("content"), dict) else {}
    missing_evidence = [str(item) for item in chair_content.get("missing_evidence", []) if str(item).strip()]
    missing_evidence.extend(
        str(item.get("claim_text") or item.get("claim_id"))
        for item in audits
        if not bool(item.get("covered"))
    )
    effective_arguments = [
        {
            "role_id": score["role_id"],
            "score": score["score"],
            "evidence_coverage": score["components"].get("evidence_coverage"),
            "chair_adoption": score["components"].get("chair_adoption"),
        }
        for score in scores
        if float(score["score"]) >= 65.0
    ]
    major_errors = [
        f"{message.get('agent_role')} 在 round {message.get('round_index')} 未完成：{message.get('error')}"
        for message in messages
        if message.get("status") != "completed" and message.get("phase_id") != "chair_synthesis"
    ]
    if any(str(review.get("status")) == "rejected" for review in reviews):
        major_errors.append("至少一个方向性建议被人工复核拒绝。")
    if outcomes and any(not bool(outcome.get("hit")) for outcome in outcomes if outcome.get("hit") is not None):
        major_errors.append("至少一个成熟方向 outcome 未命中。")
    weak_roles = [score["role_id"] for score in scores if float(score["score"]) < 50.0]
    adjustments = []
    if missing_evidence:
        adjustments.append("下次运行优先补齐未覆盖证据，再进入方向性辩论。")
    if weak_roles:
        adjustments.append(f"人工检查角色 {', '.join(weak_roles)} 的证据范围和输出契约。")
    if not adjustments:
        adjustments.append("保持当前模板，继续积累人工复核和成熟 outcome 样本。")
    source_refs = list(
        dict.fromkeys(
            [
                f"debate:{debate_id}",
                *[f"message:{message['message_id']}" for message in messages if message.get("message_id")],
                *[f"audit:{item['audit_id']}" for item in audits if item.get("audit_id")],
                *[f"review:{item['review_id']}" for item in reviews],
                *[f"outcome:{item['outcome_id']}" for item in outcomes],
            ]
        )
    )
    return {
        "effective_arguments": effective_arguments,
        "major_errors": major_errors[:20],
        "missing_evidence": list(dict.fromkeys(missing_evidence))[:30],
        "suggested_adjustments": adjustments,
        "source_refs": source_refs,
        "automatic_prompt_changes_allowed": False,
        "automatic_workflow_publish_allowed": False,
        "automatic_gate_relaxation_allowed": False,
        "hidden_reasoning_included": False,
    }


def _review_component(reviews: list[dict[str, Any]]) -> float | None:
    if not reviews:
        return None
    mapping = {"approved": 1.0, "rejected": 0.0, "pending": 0.5}
    values = [mapping.get(str(review.get("status")), 0.5) for review in reviews]
    return sum(values) / len(values)


def _outcome_component(outcomes: list[dict[str, Any]]) -> float | None:
    values = []
    for outcome in outcomes:
        if outcome.get("hit") is not None:
            values.append(1.0 if bool(outcome.get("hit")) else 0.0)
        elif outcome.get("directional_return_pct") is not None:
            values.append(1.0 if float(outcome["directional_return_pct"]) > 0 else 0.0)
    return sum(values) / len(values) if values else None


def _message_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["content"] = _loads(payload.pop("content_json", "{}"), {})
    payload["reply_to_message_ids"] = _loads(payload.pop("reply_to_json", "[]"), [])
    return payload


def _message_evidence_refs(message: dict[str, Any]) -> set[str]:
    references: set[str] = set()

    def visit(value: Any, key: str = "") -> None:
        if key == "evidence_refs" and isinstance(value, list):
            references.update(str(item) for item in value if str(item).strip())
            return
        if isinstance(value, dict):
            for item_key, item in value.items():
                visit(item, str(item_key))
        elif isinstance(value, list):
            for item in value:
                visit(item, key)

    visit(message.get("content", {}))
    return references


def _memory_contexts(candidates: list[Any]) -> list[dict[str, str | None]]:
    contexts = []
    seen = set()
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        symbol = str(candidate.get("normalized_symbol") or candidate.get("symbol") or "").strip() or None
        context = {
            "product_id": str(candidate.get("product_id") or candidate.get("candidate_id") or "").strip() or None,
            "symbol": symbol,
            "market_type": str(candidate.get("market_type") or "").strip() or None,
        }
        key = tuple(context.values())
        if key not in seen:
            contexts.append(context)
            seen.add(key)
    return contexts[:10]


def _memory_payload(row: Any) -> dict[str, Any]:
    return {
        "memory_id": row["memory_id"],
        "template_id": row["template_id"],
        "role_id": row["role_id"],
        "product_id": row["product_id"],
        "symbol": row["symbol"],
        "market_type": row["market_type"],
        "topic": row["topic"],
        "memory_type": row["memory_type"],
        "content": _loads(row["content_json"], {}),
        "source_refs": _loads(row["source_refs_json"], []),
        "quality_score": row["quality_score"],
        "status": row["status"],
        "created_at": row["created_at"],
        "expires_at": row["expires_at"],
        "last_used_at": row["last_used_at"],
        "use_count": row["use_count"],
    }


def _quality_from_scores(scores: list[dict[str, Any]]) -> float:
    if not scores:
        return 0.0
    return round(sum(float(score["score"]) for score in scores) / len(scores) / 100.0, 4)


def _learning_policy() -> dict[str, Any]:
    return {
        "automatic_prompt_changes_allowed": False,
        "automatic_workflow_publish_allowed": False,
        "automatic_gate_relaxation_allowed": False,
        "trading_execution_allowed": False,
        "hidden_reasoning_persisted": False,
    }


def _terms(value: str) -> set[str]:
    normalized = value.lower().replace("_", " ").replace("-", " ")
    return {term for term in normalized.split() if len(term) >= 2}


def _normalize_symbol(value: str) -> str:
    return value.replace("-", "").replace("_", "").upper()


def _parse_datetime(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(float(value), 4)


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _stable_id(*parts: Any) -> str:
    return hashlib.sha256(":".join(str(part) for part in parts).encode("utf-8")).hexdigest()[:32]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
