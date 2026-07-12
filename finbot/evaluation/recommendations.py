from __future__ import annotations

import hashlib
import json
import math
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from statistics import mean
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


@dataclass(frozen=True)
class RecommendationEvaluationConfig:
    default_horizon_hours: float = 24.0
    candle_interval: str = "1h"
    max_exit_lag_hours: float = 6.0
    directional_hit_threshold_pct: float = 0.0
    neutral_move_threshold_pct: float = 1.0
    max_decisions: int = 2000


class RecommendationEvaluator:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def evaluate(
        self,
        *,
        loop_run_id: str | None = None,
        config: RecommendationEvaluationConfig | None = None,
        as_of: datetime | None = None,
    ) -> dict[str, Any]:
        policy = config or RecommendationEvaluationConfig()
        evaluated_at = _aware(as_of or datetime.now(timezone.utc))
        evaluation_run_id = _hash_id(
            "recommendation-evaluation",
            loop_run_id or "standalone",
            evaluated_at.isoformat(),
            policy.default_horizon_hours,
        )
        decisions = [dict(row) for row in self.store.list_ai_trade_decisions(limit=policy.max_decisions)]
        current_outcomes = [
            self._evaluate_decision(
                decision,
                evaluation_run_id=evaluation_run_id,
                policy=policy,
                evaluated_at=evaluated_at,
            )
            for decision in decisions
        ]
        for outcome in current_outcomes:
            self.store.insert_recommendation_outcome(outcome)

        all_outcomes = [_outcome_payload(row) for row in self.store.list_recommendation_outcomes()]
        metrics = _aggregate_metrics(all_outcomes)
        status_counts = _counts(item["status"] for item in current_outcomes)
        status = "empty" if not decisions else "passed"
        summary = {
            "decision_count": len(decisions),
            "current_statuses": status_counts,
            "historical_outcome_count": len(all_outcomes),
            "evaluated_count": metrics["sample_counts"]["evaluated"],
            "directional_sample_count": metrics["sample_counts"]["directional"],
            "directional_hit_rate": metrics["directional"]["hit_rate"],
            "average_directional_return_pct": metrics["directional"]["average_return_pct"],
            "max_drawdown_pct": metrics["directional"]["max_drawdown_pct"],
            "brier_score": metrics["calibration"]["brier_score"],
            "expected_calibration_error": metrics["calibration"]["expected_calibration_error"],
        }
        report = {
            "status": status,
            "evaluation_run_id": evaluation_run_id,
            "loop_run_id": loop_run_id,
            "created_at": evaluated_at.isoformat(),
            "config": asdict(policy),
            "summary": summary,
            "metrics": metrics,
            "current_outcomes": current_outcomes,
            "methodology": {
                "entry_policy": "decision entry_reference, else latest persisted market point not later than decision_at",
                "exit_policy": "first persisted candle at or after horizon_at within max_exit_lag_hours",
                "directional_return": "BUY uses raw return; SELL uses inverse raw return",
                "neutral_hit": f"absolute raw return <= {policy.neutral_move_threshold_pct}%",
                "execution_simulated": False,
                "fees_slippage_included": False,
                "lookahead_allowed": False,
            },
        }
        self.store.insert_recommendation_evaluation_run(report)
        return report

    def _evaluate_decision(
        self,
        decision: dict[str, Any],
        *,
        evaluation_run_id: str,
        policy: RecommendationEvaluationConfig,
        evaluated_at: datetime,
    ) -> dict[str, Any]:
        decision_at = _parse_datetime(decision.get("created_at"))
        horizon_hours = _horizon_hours(decision.get("horizon"), policy.default_horizon_hours)
        horizon_at = decision_at + timedelta(hours=horizon_hours)
        action = str(decision.get("action") or "WATCH").upper()
        provider = _optional_string(decision.get("provider"))
        market_type = _optional_string(decision.get("market_type"))
        normalized_symbol = _normalized_symbol(decision.get("normalized_symbol") or decision.get("symbol"))
        payload = _json_object(decision.get("payload_json"))
        provenance = payload.get("ai_provenance") if isinstance(payload.get("ai_provenance"), dict) else {}
        base = {
            "outcome_id": _hash_id("recommendation-outcome", decision["decision_id"], horizon_hours),
            "evaluation_run_id": evaluation_run_id,
            "decision_id": decision["decision_id"],
            "loop_run_id": decision["loop_run_id"],
            "horizon_hours": horizon_hours,
            "action": action,
            "confidence": _clamp(_float(decision.get("confidence"), 0.0), 0.0, 1.0),
            "provider": provider,
            "market_type": market_type,
            "symbol": str(decision.get("symbol") or normalized_symbol),
            "normalized_symbol": normalized_symbol,
            "decision_at": decision_at.isoformat(),
            "horizon_at": horizon_at.isoformat(),
            "evaluated_at": evaluated_at.isoformat(),
            "ai_site_id": _row_or_payload(decision, "ai_site_id", provenance),
            "ai_model": _row_or_payload(decision, "ai_model", provenance),
            "prompt_version": _row_or_payload(decision, "prompt_version", provenance),
            "experiment_id": _row_or_payload(decision, "experiment_id", provenance),
            "variant_id": _row_or_payload(decision, "variant_id", provenance),
        }
        if evaluated_at < horizon_at:
            return {
                **base,
                "status": "pending",
                "reason": "horizon_not_reached",
                "entry_price": _positive_float(decision.get("entry_reference")),
                "entry_source": "decision_entry_reference" if _positive_float(decision.get("entry_reference")) else None,
            }

        entry_price, entry_source = self._entry_price(
            decision,
            provider=provider,
            market_type=market_type,
            normalized_symbol=normalized_symbol,
            decision_at=decision_at,
            interval=policy.candle_interval,
        )
        if entry_price is None:
            return {**base, "status": "insufficient_data", "reason": "missing_point_in_time_entry"}

        candles = [
            dict(row)
            for row in self.store.list_market_candles(
                provider=provider,
                market_type=market_type,
                normalized_symbol=normalized_symbol,
                interval=policy.candle_interval,
                start_at=decision_at.isoformat(),
                end_at=evaluated_at.isoformat(),
            )
        ]
        exit_candle = next(
            (row for row in candles if _parse_datetime(row.get("open_time")) >= horizon_at and _positive_float(row.get("close")) is not None),
            None,
        )
        if exit_candle is None:
            return {
                **base,
                "status": "insufficient_data",
                "reason": "missing_matured_exit_candle",
                "entry_price": entry_price,
                "entry_source": entry_source,
            }
        exit_at = _parse_datetime(exit_candle["open_time"])
        if exit_at > horizon_at + timedelta(hours=policy.max_exit_lag_hours):
            return {
                **base,
                "status": "insufficient_data",
                "reason": "exit_candle_outside_allowed_lag",
                "entry_price": entry_price,
                "entry_source": entry_source,
                "exit_at": exit_at.isoformat(),
            }

        exit_price = _positive_float(exit_candle.get("close"))
        assert exit_price is not None
        window = [row for row in candles if _parse_datetime(row["open_time"]) <= exit_at]
        raw_return_pct = (exit_price / entry_price - 1.0) * 100.0
        directional_return_pct = _directional_return(action, raw_return_pct)
        hit = _hit(
            action,
            raw_return_pct,
            directional_return_pct,
            policy.directional_hit_threshold_pct,
            policy.neutral_move_threshold_pct,
        )
        mfe_pct, mae_pct = _excursions(action, entry_price, window)
        target_hit, invalidation_hit = _level_hits(
            action,
            _positive_float(decision.get("target_price")),
            _positive_float(decision.get("invalidation_price")),
            window,
        )
        return {
            **base,
            "status": "evaluated",
            "entry_price": round(entry_price, 12),
            "entry_source": entry_source,
            "exit_price": round(exit_price, 12),
            "exit_at": exit_at.isoformat(),
            "raw_return_pct": _rounded(raw_return_pct),
            "directional_return_pct": _rounded(directional_return_pct),
            "mfe_pct": _rounded(mfe_pct),
            "mae_pct": _rounded(mae_pct),
            "hit": hit,
            "target_hit": target_hit,
            "invalidation_hit": invalidation_hit,
            "path_ambiguous": target_hit is True and invalidation_hit is True,
            "candle_count": len(window),
        }

    def _entry_price(
        self,
        decision: dict[str, Any],
        *,
        provider: str | None,
        market_type: str | None,
        normalized_symbol: str,
        decision_at: datetime,
        interval: str,
    ) -> tuple[float | None, str | None]:
        decision_entry = _positive_float(decision.get("entry_reference"))
        if decision_entry is not None:
            return decision_entry, "decision_entry_reference"
        candles = self.store.list_market_candles(
            provider=provider,
            market_type=market_type,
            normalized_symbol=normalized_symbol,
            interval=interval,
            end_at=decision_at.isoformat(),
        )
        for row in reversed(candles):
            close = _positive_float(row["close"])
            if close is not None:
                return close, "persisted_candle_at_or_before_decision"
        quotes = self.store.list_market_quotes(
            provider=provider,
            market_type=market_type,
            normalized_symbol=normalized_symbol,
            end_at=decision_at.isoformat(),
            limit=1,
        )
        if quotes:
            last_price = _positive_float(quotes[0]["last_price"])
            if last_price is not None:
                return last_price, "persisted_quote_at_or_before_decision"
        return None, None


def _aggregate_metrics(outcomes: list[dict[str, Any]]) -> dict[str, Any]:
    evaluated = [item for item in outcomes if item.get("status") == "evaluated"]
    directional = [item for item in evaluated if item.get("action") in {"BUY", "SELL"}]
    neutral = [item for item in evaluated if item.get("action") in {"HOLD", "WATCH"}]
    directional_returns = [float(item["directional_return_pct"]) for item in directional if item.get("directional_return_pct") is not None]
    calibration = _calibration(directional)
    return {
        "sample_counts": {
            "all": len(outcomes),
            "evaluated": len(evaluated),
            "directional": len(directional),
            "neutral": len(neutral),
            "pending": sum(item.get("status") == "pending" for item in outcomes),
            "insufficient_data": sum(item.get("status") == "insufficient_data" for item in outcomes),
        },
        "directional": {
            "hit_rate": _rate(directional),
            "average_return_pct": _rounded(mean(directional_returns)) if directional_returns else None,
            "cumulative_return_pct": _rounded(_compound_return(directional_returns)) if directional_returns else None,
            "max_drawdown_pct": _rounded(_max_drawdown(directional)) if directional else None,
            "target_hit_rate": _boolean_rate(directional, "target_hit"),
            "invalidation_hit_rate": _boolean_rate(directional, "invalidation_hit"),
        },
        "neutral": {"hit_rate": _rate(neutral)},
        "calibration": calibration,
        "comparisons": {
            "model": _group_metrics(directional, ("ai_site_id", "ai_model")),
            "prompt_version": _group_metrics(directional, ("prompt_version",)),
            "experiment_variant": _group_metrics(directional, ("experiment_id", "variant_id")),
        },
    }


def _calibration(outcomes: list[dict[str, Any]]) -> dict[str, Any]:
    samples = [item for item in outcomes if item.get("hit") is not None]
    if not samples:
        return {"brier_score": None, "expected_calibration_error": None, "bins": []}
    brier = mean((float(item.get("confidence") or 0.0) - float(bool(item["hit"]))) ** 2 for item in samples)
    bins = []
    ece = 0.0
    for index in range(5):
        lower = index / 5.0
        upper = (index + 1) / 5.0
        bucket = [
            item for item in samples
            if lower <= float(item.get("confidence") or 0.0) < upper or (index == 4 and float(item.get("confidence") or 0.0) == 1.0)
        ]
        if not bucket:
            continue
        average_confidence = mean(float(item.get("confidence") or 0.0) for item in bucket)
        actual_hit_rate = mean(float(bool(item["hit"])) for item in bucket)
        ece += len(bucket) / len(samples) * abs(average_confidence - actual_hit_rate)
        bins.append(
            {
                "lower": lower,
                "upper": upper,
                "sample_count": len(bucket),
                "average_confidence": _rounded(average_confidence),
                "actual_hit_rate": _rounded(actual_hit_rate),
            }
        )
    return {
        "brier_score": _rounded(brier),
        "expected_calibration_error": _rounded(ece),
        "bins": bins,
    }


def _group_metrics(outcomes: list[dict[str, Any]], keys: tuple[str, ...]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, ...], list[dict[str, Any]]] = {}
    for outcome in outcomes:
        group_key = tuple(str(outcome.get(key) or "legacy/unknown") for key in keys)
        groups.setdefault(group_key, []).append(outcome)
    result = []
    for group_key, values in groups.items():
        returns = [float(item["directional_return_pct"]) for item in values if item.get("directional_return_pct") is not None]
        result.append(
            {
                **dict(zip(keys, group_key, strict=True)),
                "sample_count": len(values),
                "hit_rate": _rate(values),
                "average_return_pct": _rounded(mean(returns)) if returns else None,
                "brier_score": _calibration(values)["brier_score"],
            }
        )
    return sorted(result, key=lambda item: (-int(item["sample_count"]), *(str(item[key]) for key in keys)))


def _max_drawdown(outcomes: list[dict[str, Any]]) -> float:
    equity = 1.0
    peak = 1.0
    max_drawdown = 0.0
    for item in sorted(outcomes, key=lambda value: (str(value.get("decision_at") or ""), str(value.get("decision_id") or ""))):
        value = item.get("directional_return_pct")
        if value is None:
            continue
        equity *= max(0.0, 1.0 + float(value) / 100.0)
        peak = max(peak, equity)
        if peak > 0:
            max_drawdown = max(max_drawdown, (peak - equity) / peak * 100.0)
    return max_drawdown


def _compound_return(values: list[float]) -> float:
    equity = math.prod(max(0.0, 1.0 + value / 100.0) for value in values)
    return (equity - 1.0) * 100.0


def _excursions(action: str, entry_price: float, candles: list[dict[str, Any]]) -> tuple[float | None, float | None]:
    if action not in {"BUY", "SELL"}:
        return None, None
    highs = [_positive_float(row.get("high")) for row in candles]
    lows = [_positive_float(row.get("low")) for row in candles]
    highs = [value for value in highs if value is not None]
    lows = [value for value in lows if value is not None]
    if not highs or not lows:
        return None, None
    if action == "BUY":
        return (max(highs) / entry_price - 1.0) * 100.0, (min(lows) / entry_price - 1.0) * 100.0
    return (1.0 - min(lows) / entry_price) * 100.0, (1.0 - max(highs) / entry_price) * 100.0


def _level_hits(
    action: str,
    target: float | None,
    invalidation: float | None,
    candles: list[dict[str, Any]],
) -> tuple[bool | None, bool | None]:
    if action not in {"BUY", "SELL"}:
        return None, None
    highs = [_positive_float(row.get("high")) for row in candles]
    lows = [_positive_float(row.get("low")) for row in candles]
    highs = [value for value in highs if value is not None]
    lows = [value for value in lows if value is not None]
    if not highs or not lows:
        return None, None
    if action == "BUY":
        return (max(highs) >= target if target is not None else None), (min(lows) <= invalidation if invalidation is not None else None)
    return (min(lows) <= target if target is not None else None), (max(highs) >= invalidation if invalidation is not None else None)


def _directional_return(action: str, raw_return_pct: float) -> float | None:
    if action == "BUY":
        return raw_return_pct
    if action == "SELL":
        return -raw_return_pct
    return None


def _hit(
    action: str,
    raw_return_pct: float,
    directional_return_pct: float | None,
    directional_threshold: float,
    neutral_threshold: float,
) -> bool:
    if action in {"BUY", "SELL"}:
        return directional_return_pct is not None and directional_return_pct > directional_threshold
    return abs(raw_return_pct) <= neutral_threshold


def _horizon_hours(value: Any, default: float) -> float:
    if isinstance(value, (int, float)) and float(value) > 0:
        return float(value)
    clean = str(value or "").strip().lower()
    try:
        if clean.endswith("h"):
            return max(1.0, float(clean[:-1]))
        if clean.endswith("d"):
            return max(1.0, float(clean[:-1]) * 24.0)
        if clean.endswith("w"):
            return max(1.0, float(clean[:-1]) * 168.0)
    except ValueError:
        pass
    return max(1.0, float(default))


def _outcome_payload(row: Any) -> dict[str, Any]:
    payload = _json_object(row["payload_json"])
    payload.setdefault("status", row["status"])
    payload.setdefault("hit", None if row["hit"] is None else bool(row["hit"]))
    payload.setdefault("target_hit", None if row["target_hit"] is None else bool(row["target_hit"]))
    payload.setdefault("invalidation_hit", None if row["invalidation_hit"] is None else bool(row["invalidation_hit"]))
    return payload


def _row_or_payload(row: dict[str, Any], key: str, payload: dict[str, Any]) -> Any:
    return row.get(key) or payload.get(key)


def _rate(values: list[dict[str, Any]]) -> float | None:
    samples = [item for item in values if item.get("hit") is not None]
    return _rounded(mean(float(bool(item["hit"])) for item in samples)) if samples else None


def _boolean_rate(values: list[dict[str, Any]], key: str) -> float | None:
    samples = [item for item in values if item.get(key) is not None]
    return _rounded(mean(float(bool(item[key])) for item in samples)) if samples else None


def _counts(values: Any) -> dict[str, int]:
    result: dict[str, int] = {}
    for value in values:
        key = str(value)
        result[key] = result.get(key, 0) + 1
    return result


def _parse_datetime(value: Any) -> datetime:
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return _aware(parsed)


def _aware(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def _normalized_symbol(value: Any) -> str:
    return str(value or "").replace("/", "").replace("-", "").replace("_", "").upper()


def _positive_float(value: Any) -> float | None:
    parsed = _float(value, 0.0)
    return parsed if parsed > 0 and math.isfinite(parsed) else None


def _float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    return parsed if math.isfinite(parsed) else default


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(float(value), 6)


def _optional_string(value: Any) -> str | None:
    clean = str(value or "").strip()
    return clean or None


def _json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not value:
        return {}
    try:
        parsed = json.loads(str(value))
    except (TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _hash_id(*parts: Any) -> str:
    payload = ":".join(str(part) for part in parts)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]
