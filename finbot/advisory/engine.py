from __future__ import annotations

import hashlib
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.market.public_exchanges import MarketCandle, MarketQuote


@dataclass(frozen=True)
class AdvisoryConfig:
    profile: str = "phase6-default"
    risk_per_trade_pct: float = 0.5
    max_position_notional_pct: float = 5.0
    reward_risk_ratio: float = 1.6
    min_confidence_for_trade: float = 0.58
    atr_lookback: int = 14
    primary_interval: str = "1h"


class AdvisoryEngine:
    def build_advice(
        self,
        quote: MarketQuote,
        candles: list[MarketCandle],
        candle_groups: dict[str, list[MarketCandle]] | None = None,
        config: AdvisoryConfig | None = None,
        research_context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        config = config or AdvisoryConfig()
        grouped_candles = _normalize_candle_groups(candles, candle_groups)
        timeframe_metrics = {
            interval: self._metrics(quote, [candle for candle in group if candle.close is not None], config)
            for interval, group in grouped_candles.items()
        }
        metrics = _primary_metrics(timeframe_metrics, config.primary_interval)
        metrics["timeframes"] = timeframe_metrics
        metrics["timeframe_alignment"] = _timeframe_alignment(timeframe_metrics)
        action = self._action(metrics, config)
        confidence = self._confidence(metrics, action)
        levels = self._levels(quote, metrics, action, config)
        created_at = datetime.now(timezone.utc).isoformat()
        payload = {
            "advice_id": self._advice_id(quote, config, created_at),
            "version": "phase6-advisory-v1",
            "profile": config.profile,
            "created_at": created_at,
            "provider": quote.provider,
            "market_type": quote.market_type,
            "symbol": quote.symbol,
            "normalized_symbol": quote.normalized_symbol,
            "action": action,
            "confidence": confidence,
            "horizon": self._horizon(grouped_candles),
            "levels": levels,
            "position_sizing": {
                "risk_per_trade_pct": config.risk_per_trade_pct,
                "max_position_notional_pct": config.max_position_notional_pct,
                "sizing_policy": "advisory-only-user-must-confirm",
            },
            "metrics": metrics,
            "rationale": self._rationale(metrics, action),
            "research_context": research_context or {},
            "policy": {
                "execution_allowed": False,
                "exchange_private_api_allowed": False,
                "order_api_allowed": False,
                "human_confirmation_required": True,
                "advisory_terms_allowed": ["BUY", "SELL", "HOLD"],
            },
            "risk_warnings": self._risk_warnings(metrics, confidence),
        }
        return payload

    def _metrics(self, quote: MarketQuote, candles: list[MarketCandle], config: AdvisoryConfig) -> dict[str, Any]:
        closes = [float(candle.close) for candle in candles if candle.close is not None]
        if not closes:
            return {
                "last_price": quote.last_price,
                "trend_pct": None,
                "momentum_pct": None,
                "volatility_pct": None,
                "quote_change_pct_24h": quote.price_change_pct_24h,
                "candle_count": 0,
            }
        trend_pct = _pct_change(closes[0], closes[-1])
        lookback = max(2, min(config.atr_lookback, len(candles)))
        recent = candles[-lookback:]
        ranges = [
            abs(float(candle.high) - float(candle.low))
            for candle in recent
            if candle.high is not None and candle.low is not None
        ]
        avg_range = sum(ranges) / len(ranges) if ranges else 0.0
        last_close = closes[-1]
        volatility_pct = round(avg_range / last_close * 100, 6) if last_close else None
        momentum_pct = _pct_change(closes[-min(6, len(closes))], closes[-1]) if len(closes) >= 2 else None
        return {
            "last_price": quote.last_price or last_close,
            "trend_pct": trend_pct,
            "momentum_pct": momentum_pct,
            "volatility_pct": volatility_pct,
            "quote_change_pct_24h": quote.price_change_pct_24h,
            "candle_count": len(candles),
            "volume_24h": quote.volume_24h,
            "turnover_24h": quote.turnover_24h,
            "spread_pct": _spread_pct(quote.bid, quote.ask, quote.last_price or last_close),
        }

    def _action(self, metrics: dict[str, Any], config: AdvisoryConfig) -> str:
        alignment = metrics.get("timeframe_alignment") or {}
        bullish_score = _float(alignment.get("average_score"))
        if bullish_score is None:
            return "HOLD"
        if bullish_score >= 1.2:
            return "BUY"
        if bullish_score <= -1.2:
            return "SELL"
        return "HOLD"

    def _confidence(self, metrics: dict[str, Any], action: str) -> float:
        if action == "HOLD":
            return 0.5
        trend = abs(_float(metrics.get("trend_pct")) or 0.0)
        momentum = abs(_float(metrics.get("momentum_pct")) or 0.0)
        candle_count = int(metrics.get("candle_count") or 0)
        confidence = 0.45 + min(0.22, trend / 40.0) + min(0.18, momentum / 30.0) + min(0.12, candle_count / 500.0)
        alignment = metrics.get("timeframe_alignment") or {}
        if alignment.get("direction") in {"bullish", "bearish"}:
            confidence += min(0.08, float(alignment.get("agreement_ratio") or 0.0) * 0.08)
        spread = _float(metrics.get("spread_pct"))
        if spread and spread > 0.2:
            confidence -= 0.08
        return round(max(0.0, min(0.95, confidence)), 2)

    def _levels(
        self,
        quote: MarketQuote,
        metrics: dict[str, Any],
        action: str,
        config: AdvisoryConfig,
    ) -> dict[str, Any]:
        last_price = _float(metrics.get("last_price")) or quote.last_price
        volatility_pct = _float(metrics.get("volatility_pct")) or 1.0
        if not last_price or action == "HOLD":
            return {
                "entry_reference": last_price,
                "target_price": None,
                "invalidation_price": None,
                "risk_distance_pct": None,
            }
        risk_distance_pct = max(0.4, min(6.0, volatility_pct))
        reward_distance_pct = risk_distance_pct * config.reward_risk_ratio
        if action == "BUY":
            invalidation = last_price * (1 - risk_distance_pct / 100)
            target = last_price * (1 + reward_distance_pct / 100)
        else:
            invalidation = last_price * (1 + risk_distance_pct / 100)
            target = last_price * (1 - reward_distance_pct / 100)
        return {
            "entry_reference": round(last_price, 8),
            "target_price": round(target, 8),
            "invalidation_price": round(invalidation, 8),
            "risk_distance_pct": round(risk_distance_pct, 4),
            "reward_risk_ratio": config.reward_risk_ratio,
        }

    def _rationale(self, metrics: dict[str, Any], action: str) -> list[str]:
        alignment = metrics.get("timeframe_alignment") or {}
        return [
            f"Action {action} is derived from multi-timeframe trend, momentum, and 24h quote change.",
            f"Trend pct: {metrics.get('trend_pct')}; momentum pct: {metrics.get('momentum_pct')}; 24h change pct: {metrics.get('quote_change_pct_24h')}.",
            f"Timeframe alignment: {alignment.get('direction')} with average score {alignment.get('average_score')}.",
            "This is an advisory output only and cannot execute orders.",
        ]

    def _risk_warnings(self, metrics: dict[str, Any], confidence: float) -> list[str]:
        warnings = []
        if confidence < 0.58:
            warnings.append("Confidence is below the default trade threshold; prefer HOLD or manual review.")
        if not metrics.get("candle_count"):
            warnings.append("No candle history was available; advice is based on incomplete market context.")
        spread = _float(metrics.get("spread_pct"))
        if spread and spread > 0.2:
            warnings.append("Spread is wide; execution quality may be poor.")
        return warnings

    def _horizon(self, candle_groups: dict[str, list[MarketCandle]]) -> str:
        intervals = [interval for interval, candles in candle_groups.items() if candles]
        if not intervals:
            return "unknown"
        return "/".join(intervals) + "-context"

    def _advice_id(self, quote: MarketQuote, config: AdvisoryConfig, created_at: str) -> str:
        value = f"{config.profile}:{quote.provider}:{quote.market_type}:{quote.symbol}:{created_at}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def config_to_dict(config: AdvisoryConfig) -> dict[str, Any]:
    return asdict(config)


def _pct_change(start: float, end: float) -> float | None:
    if not start:
        return None
    return round((end - start) / start * 100, 6)


def _normalize_candle_groups(
    candles: list[MarketCandle],
    candle_groups: dict[str, list[MarketCandle]] | None,
) -> dict[str, list[MarketCandle]]:
    if candle_groups:
        return {interval: list(group) for interval, group in candle_groups.items()}
    if not candles:
        return {}
    return {candles[-1].interval: candles}


def _primary_metrics(timeframe_metrics: dict[str, dict[str, Any]], primary_interval: str) -> dict[str, Any]:
    if primary_interval in timeframe_metrics:
        return dict(timeframe_metrics[primary_interval])
    for metrics in timeframe_metrics.values():
        return dict(metrics)
    return {
        "last_price": None,
        "trend_pct": None,
        "momentum_pct": None,
        "volatility_pct": None,
        "quote_change_pct_24h": None,
        "candle_count": 0,
    }


def _timeframe_alignment(timeframe_metrics: dict[str, dict[str, Any]]) -> dict[str, Any]:
    scored = []
    for interval, metrics in timeframe_metrics.items():
        score = _score_metrics(metrics)
        if score is not None:
            direction = "bullish" if score >= 0.35 else "bearish" if score <= -0.35 else "neutral"
            scored.append({"interval": interval, "score": score, "direction": direction})
    if not scored:
        return {"direction": "unknown", "average_score": None, "agreement_ratio": 0.0, "timeframes": []}
    average_score = round(sum(item["score"] for item in scored) / len(scored), 6)
    direction = "bullish" if average_score >= 0.35 else "bearish" if average_score <= -0.35 else "neutral"
    agreeing = [item for item in scored if item["direction"] == direction]
    agreement_ratio = round(len(agreeing) / len(scored), 4) if direction != "neutral" else 0.0
    return {
        "direction": direction,
        "average_score": average_score,
        "agreement_ratio": agreement_ratio,
        "timeframes": scored,
    }


def _score_metrics(metrics: dict[str, Any]) -> float | None:
    trend = _float(metrics.get("trend_pct"))
    momentum = _float(metrics.get("momentum_pct"))
    quote_change = _float(metrics.get("quote_change_pct_24h")) or 0.0
    if trend is None or momentum is None:
        return None
    return round(trend * 0.45 + momentum * 0.35 + quote_change * 0.20, 6)


def _spread_pct(bid: float | None, ask: float | None, last_price: float | None) -> float | None:
    if bid is None or ask is None or not last_price:
        return None
    return round((ask - bid) / last_price * 100, 6)


def _float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
