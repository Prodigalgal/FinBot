from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.evaluation.recommendations import RecommendationEvaluationConfig, RecommendationEvaluator
from finbot.storage.sqlite_store import SQLiteStore
from finbot.workspace.reviews import DecisionReviewService


@dataclass(frozen=True)
class ShadowPortfolioConfig:
    initial_cash_usdt: float = 100_000.0
    position_notional_usdt: float = 1_000.0


class ResearchFeedbackService:
    def __init__(self, store: SQLiteStore, shadow_config: ShadowPortfolioConfig | None = None):
        self.store = store
        self.shadow_config = shadow_config or ShadowPortfolioConfig()
        self.store.init_schema()

    def snapshot(self) -> dict[str, Any]:
        evaluation = self._latest_evaluation()
        shadow = self._latest_shadow_snapshot()
        reflections = self._reflections(evaluation, shadow)
        notifications = self.list_notifications(limit=50)
        return {
            "status": "ok",
            "generated_at": _now(),
            "shadow_portfolio": shadow,
            "backtest": self._backtest(evaluation),
            "calibration": self._calibration(evaluation),
            "reflections": reflections,
            "notifications": notifications,
            "policy": {
                "real_account_data_used": False,
                "real_orders_used": False,
                "lookahead_allowed": False,
                "automatic_prompt_changes_allowed": False,
                "external_notification_delivery": False,
            },
        }

    def refresh(self, *, as_of: datetime | None = None) -> dict[str, Any]:
        evaluation = RecommendationEvaluator(self.store).evaluate(
            config=RecommendationEvaluationConfig(),
            as_of=as_of,
        )
        shadow = self._refresh_shadow()
        reflections = self._reflections(evaluation, shadow)
        self._generate_notifications(evaluation, shadow, reflections)
        return self.snapshot()

    def list_notifications(self, *, status: str | None = None, limit: int = 100) -> dict[str, Any]:
        if status and status not in {"unread", "read", "dismissed"}:
            raise ValueError("通知状态必须是 unread、read 或 dismissed")
        rows = [_notification_payload(row) for row in self.store.list_notifications(status, limit)]
        counts: dict[str, int] = {}
        for row in self.store.list_notifications(limit=500):
            item_status = str(row["status"])
            counts[item_status] = counts.get(item_status, 0) + 1
        return {"status": "ok", "count": len(rows), "counts": counts, "items": rows}

    def update_notification(self, notification_id: str, status: str) -> dict[str, Any]:
        if status not in {"read", "dismissed"}:
            raise ValueError("通知只能标记为 read 或 dismissed")
        if not self.store.update_notification_status(notification_id, status, _now()):
            raise LookupError(f"未找到通知：{notification_id}")
        row = next(
            (item for item in self.store.list_notifications(limit=500) if item["notification_id"] == notification_id),
            None,
        )
        return {"status": "ok", "notification": _notification_payload(row)}

    def _refresh_shadow(self) -> dict[str, Any]:
        review_service = DecisionReviewService(self.store)
        approved_reviews = self.store.list_decision_reviews(status="approved", limit=500)
        outcomes = {
            str(item["decision_id"]): _outcome_payload(item)
            for item in self.store.list_recommendation_outcomes()
            if item["status"] == "evaluated"
        }
        latest_loop_run_id = None
        for review in approved_reviews:
            decision_id = str(review["decision_id"])
            try:
                decision = review_service.approved_decision(decision_id)
            except (LookupError, ValueError):
                continue
            latest_loop_run_id = latest_loop_run_id or decision.get("loop_run_id")
            self.store.upsert_shadow_position(
                self._position_payload(decision, dict(review), outcomes.get(decision_id))
            )
        positions = [_shadow_position_payload(row) for row in self.store.list_shadow_positions(limit=2000)]
        open_positions = [item for item in positions if item["status"] == "open"]
        realized = sum(float(item.get("realized_pnl_usdt") or 0.0) for item in positions)
        unrealized = sum(float(item.get("unrealized_pnl_usdt") or 0.0) for item in open_positions)
        cash = self.shadow_config.initial_cash_usdt + realized
        equity = cash + unrealized
        gross_exposure = sum(
            float(item["quantity"]) * float(item.get("current_price") or item["entry_price"])
            for item in open_positions
            if item.get("current_price") is not None
        )
        previous_snapshots = self.store.list_shadow_portfolio_snapshots(limit=500)
        historical_peaks = [float(item["peak_equity_usdt"] or 0.0) for item in previous_snapshots]
        peak = max([self.shadow_config.initial_cash_usdt, equity, *historical_peaks])
        drawdown = (peak - equity) / peak * 100.0 if peak > 0 else None
        missing_marks = sum(item.get("current_price") is None for item in open_positions)
        status = "empty" if not positions else "insufficient_data" if missing_marks else "ready"
        created_at = _now()
        snapshot = {
            "snapshot_id": _stable_id("shadow-snapshot", created_at),
            "status": status,
            "source_loop_run_id": latest_loop_run_id,
            "equity_usdt": _rounded(equity),
            "cash_usdt": _rounded(cash),
            "gross_exposure_usdt": _rounded(gross_exposure),
            "realized_pnl_usdt": _rounded(realized),
            "unrealized_pnl_usdt": _rounded(unrealized),
            "peak_equity_usdt": _rounded(peak),
            "drawdown_pct": _rounded(drawdown),
            "positions": positions,
            "metrics": {
                "position_count": len(positions),
                "open_position_count": len(open_positions),
                "closed_position_count": len(positions) - len(open_positions),
                "missing_mark_count": missing_marks,
                "mark_coverage": _rounded((len(open_positions) - missing_marks) / len(open_positions)) if open_positions else None,
                "initial_cash_usdt": self.shadow_config.initial_cash_usdt,
                "position_notional_usdt": self.shadow_config.position_notional_usdt,
            },
            "created_at": created_at,
        }
        self.store.insert_shadow_portfolio_snapshot(snapshot)
        return snapshot

    def _position_payload(
        self,
        decision: dict[str, Any],
        review: dict[str, Any],
        outcome: dict[str, Any] | None,
    ) -> dict[str, Any]:
        entry = _positive_float(decision.get("entry_reference"))
        if entry is None:
            raise ValueError("已批准决策缺少有效 entry_reference")
        action = str(decision["action"]).upper()
        quantity = self.shadow_config.position_notional_usdt / entry
        existing = self.store.get_shadow_position(str(decision["decision_id"]))
        opened_at = existing["opened_at"] if existing is not None else review.get("reviewed_at") or decision["created_at"]
        current_price = None
        exit_price = None
        status = "open"
        closed_at = None
        realized = None
        unrealized = None
        if outcome and _positive_float(outcome.get("exit_price")) is not None:
            exit_price = _positive_float(outcome["exit_price"])
            current_price = exit_price
            status = "closed"
            closed_at = outcome.get("exit_at") or outcome.get("evaluated_at")
            realized = _pnl(action, quantity, entry, exit_price)
            unrealized = 0.0
        else:
            quotes = self.store.list_market_quotes(
                provider=decision.get("provider"),
                market_type=decision.get("market_type"),
                normalized_symbol=decision.get("normalized_symbol"),
                limit=1,
            )
            if quotes:
                current_price = _positive_float(quotes[0]["last_price"])
            if current_price is not None:
                unrealized = _pnl(action, quantity, entry, current_price)
        return {
            "position_id": _stable_id("shadow-position", decision["decision_id"]),
            "decision_id": decision["decision_id"],
            "review_id": review["review_id"],
            "loop_run_id": decision["loop_run_id"],
            "provider": decision.get("provider"),
            "market_type": decision.get("market_type"),
            "symbol": decision["symbol"],
            "normalized_symbol": decision.get("normalized_symbol"),
            "side": action,
            "status": status,
            "quantity": quantity,
            "notional_usdt": self.shadow_config.position_notional_usdt,
            "entry_price": entry,
            "current_price": current_price,
            "exit_price": exit_price,
            "unrealized_pnl_usdt": _rounded(unrealized),
            "realized_pnl_usdt": _rounded(realized),
            "opened_at": opened_at,
            "marked_at": _now() if current_price is not None else None,
            "closed_at": closed_at,
            "metadata": {
                "review_version": review["version"],
                "confidence": decision.get("confidence"),
                "horizon": decision.get("horizon"),
                "real_account_position": False,
            },
        }

    def _latest_evaluation(self) -> dict[str, Any]:
        rows = self.store.list_recommendation_evaluation_runs(limit=1)
        return _evaluation_payload(rows[0]) if rows else {"status": "empty", "summary": {}, "metrics": {}}

    def _latest_shadow_snapshot(self) -> dict[str, Any]:
        rows = self.store.list_shadow_portfolio_snapshots(limit=1)
        return _shadow_snapshot_payload(rows[0]) if rows else {
            "status": "empty",
            "positions": [],
            "metrics": {"position_count": 0, "open_position_count": 0, "missing_mark_count": 0},
        }

    def _backtest(self, evaluation: dict[str, Any]) -> dict[str, Any]:
        summary = evaluation.get("summary") if isinstance(evaluation.get("summary"), dict) else {}
        metrics = evaluation.get("metrics") if isinstance(evaluation.get("metrics"), dict) else {}
        sample_count = int(summary.get("directional_sample_count") or 0)
        directional = metrics.get("directional") if isinstance(metrics.get("directional"), dict) else {}
        return {
            "status": "ready" if sample_count > 0 else "insufficient_data",
            "sample_count": sample_count,
            "evaluated_count": int(summary.get("evaluated_count") or 0),
            "pending_count": int(((metrics.get("sample_counts") or {}).get("pending")) or 0),
            "insufficient_data_count": int(((metrics.get("sample_counts") or {}).get("insufficient_data")) or 0),
            "hit_rate": summary.get("directional_hit_rate"),
            "average_return_pct": summary.get("average_directional_return_pct"),
            "cumulative_return_pct": directional.get("cumulative_return_pct"),
            "max_drawdown_pct": summary.get("max_drawdown_pct"),
            "methodology": evaluation.get("methodology") or {},
        }

    def _calibration(self, evaluation: dict[str, Any]) -> dict[str, Any]:
        summary = evaluation.get("summary") if isinstance(evaluation.get("summary"), dict) else {}
        metrics = evaluation.get("metrics") if isinstance(evaluation.get("metrics"), dict) else {}
        calibration = metrics.get("calibration") if isinstance(metrics.get("calibration"), dict) else {}
        sample_count = int(summary.get("directional_sample_count") or 0)
        return {
            "status": "ready" if sample_count >= 10 else "insufficient_data",
            "sample_count": sample_count,
            "brier_score": summary.get("brier_score"),
            "expected_calibration_error": summary.get("expected_calibration_error"),
            "bins": calibration.get("bins") or [],
        }

    def _reflections(self, evaluation: dict[str, Any], shadow: dict[str, Any]) -> list[dict[str, Any]]:
        backtest = self._backtest(evaluation)
        calibration = self._calibration(evaluation)
        reflections: list[dict[str, Any]] = []
        if backtest["sample_count"] < 10:
            reflections.append(
                {
                    "code": "directional_sample_insufficient",
                    "severity": "info",
                    "title": "方向样本不足",
                    "detail": f"当前只有 {backtest['sample_count']} 个到期方向样本，暂不据此调整 Prompt 或阈值。",
                    "automatic_change_allowed": False,
                }
            )
        elif calibration.get("expected_calibration_error") is not None and float(calibration["expected_calibration_error"]) > 0.15:
            reflections.append(
                {
                    "code": "confidence_miscalibrated",
                    "severity": "warning",
                    "title": "置信度校准偏差较高",
                    "detail": "建议人工复核置信度阈值和证据门禁，不自动修改模型或 Prompt。",
                    "automatic_change_allowed": False,
                }
            )
        if backtest.get("max_drawdown_pct") is not None and float(backtest["max_drawdown_pct"]) > 10.0:
            reflections.append(
                {
                    "code": "historical_drawdown_high",
                    "severity": "warning",
                    "title": "历史建议回撤超过阈值",
                    "detail": f"point-in-time 建议序列最大回撤为 {float(backtest['max_drawdown_pct']):.2f}%。",
                    "automatic_change_allowed": False,
                }
            )
        drawdown = shadow.get("drawdown_pct")
        if drawdown is not None and float(drawdown) > 5.0:
            reflections.append(
                {
                    "code": "shadow_drawdown_attention",
                    "severity": "warning",
                    "title": "Shadow Portfolio 回撤需要关注",
                    "detail": f"当前虚拟组合回撤为 {float(drawdown):.2f}%。",
                    "automatic_change_allowed": False,
                }
            )
        return reflections

    def _generate_notifications(
        self,
        evaluation: dict[str, Any],
        shadow: dict[str, Any],
        reflections: list[dict[str, Any]],
    ) -> None:
        now = _now()
        for decision in self.store.list_ai_trade_decisions(limit=200):
            if str(decision["status"]).lower() != "candidate" or str(decision["action"]).upper() not in {"BUY", "SELL"}:
                continue
            review = self.store.get_decision_review(str(decision["decision_id"]))
            if review is not None and review["status"] != "pending":
                continue
            self._notify(
                category="review",
                severity="info",
                title="有新的方向性建议待复核",
                body=f"{decision['symbol']} · {decision['action']} · 置信度 {float(decision['confidence']) * 100:.0f}%",
                entity_type="decision",
                entity_id=str(decision["decision_id"]),
                dedupe_key=f"review:{decision['decision_id']}",
                payload={"loop_run_id": decision["loop_run_id"]},
                created_at=now,
            )
        evaluation_id = str(evaluation.get("evaluation_run_id") or "none")
        for reflection in reflections:
            if reflection["severity"] == "info" and reflection["code"] == "directional_sample_insufficient":
                continue
            self._notify(
                category="feedback",
                severity=reflection["severity"],
                title=reflection["title"],
                body=reflection["detail"],
                entity_type="evaluation",
                entity_id=evaluation_id,
                dedupe_key=f"feedback:{reflection['code']}:{evaluation_id}",
                payload={"code": reflection["code"]},
                created_at=now,
            )
        if shadow.get("snapshot_id") and shadow.get("status") == "insufficient_data":
            missing_position_ids = sorted(
                str(position.get("position_id") or position.get("decision_id") or "unknown")
                for position in shadow.get("positions") or []
                if position.get("status") == "open" and position.get("current_price") is None
            )
            missing_state = _stable_id(*missing_position_ids)[:16]
            self._notify(
                category="shadow_portfolio",
                severity="warning",
                title="Shadow Portfolio 缺少最新标记价格",
                body=f"{int((shadow.get('metrics') or {}).get('missing_mark_count') or 0)} 个虚拟持仓暂时无法估值。",
                entity_type="shadow_snapshot",
                entity_id=str(shadow["snapshot_id"]),
                dedupe_key=f"shadow-missing-mark:{now[:10]}:{missing_state}",
                payload={},
                created_at=now,
            )

    def _notify(self, **notification: Any) -> None:
        self.store.insert_notification_if_absent(
            {
                "notification_id": _stable_id("notification", notification["dedupe_key"]),
                "status": "unread",
                **notification,
            }
        )


def _pnl(action: str, quantity: float, entry: float, mark: float | None) -> float | None:
    if mark is None:
        return None
    direction = 1.0 if action == "BUY" else -1.0
    return (mark - entry) * quantity * direction


def _evaluation_payload(row: Any) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    return payload if isinstance(payload, dict) else {}


def _outcome_payload(row: Any) -> dict[str, Any]:
    payload = _loads(row["payload_json"], {})
    return payload if isinstance(payload, dict) else {}


def _shadow_position_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["metadata"] = _loads(payload.pop("metadata_json", "{}"), {})
    return payload


def _shadow_snapshot_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["positions"] = _loads(payload.pop("positions_json", "[]"), [])
    payload["metrics"] = _loads(payload.pop("metrics_json", "{}"), {})
    return payload


def _notification_payload(row: Any | None) -> dict[str, Any]:
    if row is None:
        return {}
    payload = dict(row)
    payload["payload"] = _loads(payload.pop("payload_json", "{}"), {})
    return payload


def _positive_float(value: Any) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(float(value), 6)


def _stable_id(*parts: Any) -> str:
    return hashlib.sha256(":".join(str(part) for part in parts).encode("utf-8")).hexdigest()


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
