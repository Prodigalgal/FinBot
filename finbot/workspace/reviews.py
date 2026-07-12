from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


REVIEW_STATUSES = frozenset({"pending", "approved", "rejected", "changes_requested"})
DIRECTIONAL_ACTIONS = frozenset({"BUY", "SELL"})


class DecisionReviewService:
    def __init__(self, store: SQLiteStore):
        self.store = store
        self.store.init_schema()

    def list_inbox(self, *, status: str | None = None, limit: int = 100) -> dict[str, Any]:
        normalized_status = _optional_status(status)
        decisions = self.store.list_ai_trade_decisions(limit=max(1, min(limit * 4, 500)))
        items: list[dict[str, Any]] = []
        for row in decisions:
            decision = _decision_payload(row)
            review_row = self.store.get_decision_review(str(decision["decision_id"]))
            review = _review_payload(review_row, decision)
            if normalized_status and review["status"] != normalized_status:
                continue
            readiness = self._decision_readiness(str(decision["loop_run_id"]))
            blockers = self._approval_blockers(decision, readiness)
            items.append(
                {
                    "decision": decision,
                    "review": review,
                    "decision_readiness": readiness,
                    "approval_eligible": not blockers,
                    "approval_blockers": blockers,
                }
            )
            if len(items) >= limit:
                break
        counts: dict[str, int] = {}
        for row in self.store.list_decision_reviews(limit=500):
            row_status = str(row["status"])
            counts[row_status] = counts.get(row_status, 0) + 1
        implicit_pending = sum(1 for row in decisions if self.store.get_decision_review(str(row["decision_id"])) is None)
        counts["pending"] = counts.get("pending", 0) + implicit_pending
        return {"status": "ok", "count": len(items), "counts": counts, "items": items}

    def get_review(self, decision_id: str) -> dict[str, Any]:
        decision_row = self.store.get_ai_trade_decision(decision_id)
        if decision_row is None:
            raise LookupError(f"未找到 AI 决策：{decision_id}")
        decision = _decision_payload(decision_row)
        review = _review_payload(self.store.get_decision_review(decision_id), decision)
        readiness = self._decision_readiness(str(decision["loop_run_id"]))
        blockers = self._approval_blockers(decision, readiness)
        return {
            "decision": decision,
            "review": review,
            "decision_readiness": readiness,
            "approval_eligible": not blockers,
            "approval_blockers": blockers,
        }

    def review_decision(
        self,
        decision_id: str,
        *,
        status: str,
        note: str = "",
        expected_version: int | None = None,
        reviewer_id: str = "local",
    ) -> dict[str, Any]:
        normalized_status = _required_status(status)
        detail = self.get_review(decision_id)
        if normalized_status == "approved" and detail["approval_blockers"]:
            raise ValueError("当前决策不可批准：" + "；".join(detail["approval_blockers"]))
        now = _now()
        existing = detail["review"]
        saved = self.store.save_decision_review(
            {
                "review_id": existing["review_id"],
                "decision_id": decision_id,
                "loop_run_id": detail["decision"]["loop_run_id"],
                "status": normalized_status,
                "reviewer_id": reviewer_id,
                "note": " ".join(note.split()).strip()[:2000],
                "metadata": {
                    "approval_blockers_at_review": detail["approval_blockers"],
                    "decision_readiness_status": detail["decision_readiness"].get("status"),
                },
                "created_at": existing["created_at"] or now,
                "updated_at": now,
                "reviewed_at": now if normalized_status != "pending" else None,
            },
            expected_version=expected_version,
        )
        return self.get_review(str(saved["decision_id"]))

    def approved_decision(self, decision_id: str) -> dict[str, Any]:
        detail = self.get_review(decision_id)
        if detail["review"]["status"] != "approved":
            raise ValueError("决策尚未通过人工复核")
        if detail["approval_blockers"]:
            raise ValueError("决策执行门禁未通过：" + "；".join(detail["approval_blockers"]))
        return {
            **detail["decision"],
            "human_review_status": "approved",
            "human_review_id": detail["review"]["review_id"],
            "human_review_version": detail["review"]["version"],
        }

    def execution_context(self, decision_id: str) -> dict[str, Any]:
        decision = self.approved_decision(decision_id)
        loop_run_id = str(decision["loop_run_id"])
        risk_row = self.store.latest_portfolio_risk_report(loop_run_id)
        governance_row = self.store.latest_ai_governance_report(loop_run_id)
        return {
            "decision": decision,
            "loop_run_id": loop_run_id,
            "portfolio_risk": _report_payload(risk_row),
            "ai_governance": _report_payload(governance_row),
        }

    def _decision_readiness(self, loop_run_id: str) -> dict[str, Any]:
        row = self.store.get_autonomous_loop_run(loop_run_id)
        if row is None:
            return {"status": "blocked", "simulation_eligible": False, "reasons": ["missing_loop_run"]}
        summary = _loads(row["summary_json"], {})
        readiness = summary.get("decision_readiness") if isinstance(summary, dict) else None
        return readiness if isinstance(readiness, dict) else {
            "status": "blocked",
            "simulation_eligible": False,
            "reasons": ["missing_decision_readiness"],
        }

    def _approval_blockers(self, decision: dict[str, Any], readiness: dict[str, Any]) -> list[str]:
        blockers: list[str] = []
        if str(decision.get("status") or "").lower() != "candidate":
            blockers.append("决策状态不是 candidate")
        if str(decision.get("action") or "").upper() not in DIRECTIONAL_ACTIONS:
            blockers.append("只有 BUY/SELL 方向性决策可以批准执行")
        if not bool(readiness.get("simulation_eligible")):
            blockers.append("本轮决策就绪度不允许模拟执行")
        if not _valid_levels(decision):
            blockers.append("target/invalidation 与方向或 entry 不一致")

        loop_run_id = str(decision.get("loop_run_id") or "")
        risk = _report_payload(self.store.latest_portfolio_risk_report(loop_run_id))
        governance = _report_payload(self.store.latest_ai_governance_report(loop_run_id))
        if str((risk.get("risk_gate") or {}).get("status") or "").lower() not in {"passed", "warning"}:
            blockers.append("Portfolio Risk 门禁未通过")
        if str((governance.get("summary") or {}).get("governance_status") or "").lower() != "passed":
            blockers.append("AI Governance 门禁未通过")
        return blockers


def _decision_payload(row: Any) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    if not isinstance(payload, dict):
        payload = {}
    for key in (
        "decision_id", "loop_run_id", "debate_id", "source_report_id", "candidate_id",
        "provider", "market_type", "symbol", "normalized_symbol", "action", "status",
        "confidence", "score", "horizon", "entry_reference", "target_price",
        "invalidation_price", "ai_site_id", "ai_model", "prompt_version", "experiment_id",
        "variant_id", "created_at",
    ):
        payload[key] = row[key]
    for key, column, fallback in (
        ("position_sizing", "position_sizing_json", {}),
        ("rationale", "rationale_json", []),
        ("risk_warnings", "risk_warnings_json", []),
        ("evidence_refs", "evidence_refs_json", []),
        ("policy", "policy_json", {}),
    ):
        payload[key] = _loads(row[column], fallback)
    return payload


def _review_payload(row: Any | None, decision: dict[str, Any]) -> dict[str, Any]:
    if row is None:
        created_at = str(decision.get("created_at") or "")
        return {
            "review_id": _stable_id("decision-review", decision["decision_id"]),
            "decision_id": decision["decision_id"],
            "loop_run_id": decision["loop_run_id"],
            "status": "pending",
            "reviewer_id": "local",
            "note": "",
            "version": 0,
            "metadata": {},
            "created_at": created_at,
            "updated_at": created_at,
            "reviewed_at": None,
        }
    payload = dict(row)
    payload["metadata"] = _loads(payload.pop("metadata_json", "{}"), {})
    return payload


def _report_payload(row: Any | None) -> dict[str, Any]:
    if row is None:
        return {}
    payload = _loads(row["payload_json"], {})
    return payload if isinstance(payload, dict) else {}


def _valid_levels(decision: dict[str, Any]) -> bool:
    try:
        entry = float(decision.get("entry_reference") or 0)
        target = float(decision.get("target_price") or 0)
        invalidation = float(decision.get("invalidation_price") or 0)
    except (TypeError, ValueError):
        return False
    action = str(decision.get("action") or "").upper()
    if min(entry, target, invalidation) <= 0:
        return False
    return target > entry > invalidation if action == "BUY" else target < entry < invalidation


def _required_status(status: str) -> str:
    normalized = str(status or "").strip().lower()
    if normalized not in REVIEW_STATUSES:
        raise ValueError("复核状态必须是 pending、approved、rejected 或 changes_requested")
    return normalized


def _optional_status(status: str | None) -> str | None:
    return _required_status(status) if status else None


def _stable_id(*parts: Any) -> str:
    return hashlib.sha256(":".join(str(part) for part in parts).encode("utf-8")).hexdigest()


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
