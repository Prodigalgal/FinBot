from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any


MARKET_CONFIRMATION_MIN_PROVIDERS = 2
MARKET_CONFIRMATION_MIN_CONFIDENCE = 0.60
MARKET_CONFIRMATION_MAX_PRICE_DIVERGENCE_PCT = 1.0


@dataclass(frozen=True)
class ProductCandidateConfig:
    symbols: tuple[str, ...] = ("BTCUSDT",)
    limit: int = 10


class ProductCandidateBuilder:
    def build(
        self,
        operator_report: dict[str, Any] | None,
        config: ProductCandidateConfig | None = None,
    ) -> dict[str, Any]:
        config = config or ProductCandidateConfig()
        report = operator_report or {}
        candidates = self._market_candidates(report)
        candidates.extend(self._research_only_candidates(report, config))
        candidates = _dedupe_candidates(candidates)
        candidates.sort(key=lambda item: (-_float(item.get("score"), 0.0), item.get("symbol") or ""))
        selected = candidates[: max(1, config.limit)]
        return {
            "status": "ok" if selected else "empty",
            "source_report_id": report.get("report_id"),
            "candidate_count": len(candidates),
            "selected_count": len(selected),
            "candidates": selected,
            "research_context": report.get("research_context") if isinstance(report.get("research_context"), dict) else {},
        }

    def _market_candidates(self, report: dict[str, Any]) -> list[dict[str, Any]]:
        candidates: list[dict[str, Any]] = []
        market_confirmations = _cross_venue_market_confirmations(report)
        for item in report.get("items", []):
            if not isinstance(item, dict) or item.get("status") != "ok":
                continue
            advice = item.get("advice")
            if not isinstance(advice, dict):
                continue
            quote = item.get("quote") if isinstance(item.get("quote"), dict) else {}
            levels = advice.get("levels") if isinstance(advice.get("levels"), dict) else {}
            metrics = advice.get("metrics") if isinstance(advice.get("metrics"), dict) else {}
            research_context = advice.get("research_context") if isinstance(advice.get("research_context"), dict) else {}
            matched_items = research_context.get("matched_items") if isinstance(research_context.get("matched_items"), list) else []
            confidence = _float(advice.get("confidence"), 0.0)
            action = str(advice.get("action") or "HOLD").upper()
            provider = str(advice.get("provider") or item.get("provider") or "")
            market_type = str(advice.get("market_type") or item.get("market_type") or "")
            symbol = str(advice.get("symbol") or item.get("symbol") or "")
            normalized_symbol = str(advice.get("normalized_symbol") or quote.get("normalized_symbol") or symbol)
            compact_research = _compact_research_context(research_context)
            market_confirmation = market_confirmations.get(_normalize_symbol(normalized_symbol))
            if market_confirmation and market_confirmation.get("action") == action:
                compact_research = {
                    **compact_research,
                    "status": "market-confirmed",
                    "confirmation_type": "cross-venue-market",
                    "confirmation_scope": "market-only",
                    "fundamental_research_status": compact_research.get("status"),
                    "fundamental_research_confirmed": _fundamental_research_confirmed(compact_research),
                    "market_confirmation": market_confirmation,
                }
            evidence_refs = _evidence_refs(research_context, provider, symbol)
            if compact_research.get("status") == "market-confirmed":
                evidence_refs.extend(
                    f"market:{observation['provider']}:{observation['symbol']}"
                    for observation in market_confirmation.get("observations", [])
                )
            candidate = {
                "candidate_id": _candidate_id(report.get("report_id"), provider, market_type, symbol, "market"),
                "instrument_id": item.get("instrument_id"),
                "source": "operator_workbench",
                "symbol": symbol,
                "normalized_symbol": normalized_symbol,
                "provider": provider,
                "market_type": market_type,
                "status": "candidate" if action in {"BUY", "SELL"} else "watch",
                "market_action": action,
                "market_confidence": confidence,
                "score": round(
                    confidence * 100
                    + min(12, len(matched_items) * 3)
                    + (8 if compact_research.get("status") == "market-confirmed" else 0),
                    4,
                ),
                "horizon": advice.get("horizon"),
                "quote": _compact_quote(quote),
                "levels": {
                    "entry_reference": levels.get("entry_reference"),
                    "target_price": levels.get("target_price"),
                    "invalidation_price": levels.get("invalidation_price"),
                    "risk_distance_pct": levels.get("risk_distance_pct"),
                    "reward_risk_ratio": levels.get("reward_risk_ratio"),
                },
                "metrics": _compact_metrics(metrics),
                "deterministic_rationale": list(advice.get("rationale") or [])[:6],
                "deterministic_risk_warnings": list(advice.get("risk_warnings") or [])[:6],
                "research_context": compact_research,
                "evidence_refs": list(dict.fromkeys(evidence_refs)),
            }
            candidates.append(candidate)
        return candidates

    def _research_only_candidates(
        self,
        report: dict[str, Any],
        config: ProductCandidateConfig,
    ) -> list[dict[str, Any]]:
        research_context = report.get("research_context") if isinstance(report.get("research_context"), dict) else {}
        items = research_context.get("items") if isinstance(research_context.get("items"), list) else []
        verified_instruments = _verified_instruments(report)
        candidates: list[dict[str, Any]] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            for asset in _impact_assets(item):
                for instrument in _instruments_for_asset(asset, verified_instruments):
                    symbol = str(instrument["symbol"])
                    provider = str(instrument["provider"])
                    market_type = str(instrument["market_type"])
                    confidence = _float(item.get("confidence"), 0.0)
                    candidates.append(
                        {
                            "candidate_id": _candidate_id(report.get("report_id"), provider, market_type, symbol, str(item.get("event_key") or asset)),
                            "instrument_id": instrument.get("instrument_id"),
                            "source": "research_context",
                            "symbol": symbol,
                            "normalized_symbol": instrument.get("normalized_symbol") or symbol,
                            "provider": provider,
                            "market_type": market_type,
                            "status": "watch",
                            "market_action": "WATCH",
                            "market_confidence": 0.0,
                            "score": round(20 + confidence * 20, 4),
                            "horizon": "research-watch",
                            "quote": {},
                            "levels": {},
                            "metrics": {},
                            "deterministic_rationale": [],
                            "deterministic_risk_warnings": ["缺少本轮交易所行情确认，仅作为研究观察候选。"],
                            "research_context": {
                                "source": research_context.get("source"),
                                "status": research_context.get("status"),
                                "council_id": research_context.get("council_id"),
                                "pipeline_run_id": research_context.get("pipeline_run_id"),
                                "matched_items_count": 1,
                                "matched_items": [item],
                            },
                            "evidence_refs": _evidence_refs({"matched_items": [item]}, provider, symbol),
                        }
                    )
        return candidates


def _compact_quote(quote: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "provider",
        "market_type",
        "symbol",
        "normalized_symbol",
        "captured_at",
        "last_price",
        "bid",
        "ask",
        "price_change_pct_24h",
        "volume_24h",
        "turnover_24h",
    )
    return {key: quote.get(key) for key in keys if key in quote}


def _compact_metrics(metrics: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "last_price",
        "trend_pct",
        "momentum_pct",
        "volatility_pct",
        "quote_change_pct_24h",
        "candle_count",
        "spread_pct",
        "timeframe_alignment",
    )
    return {key: metrics.get(key) for key in keys if key in metrics}


def _compact_research_context(research_context: dict[str, Any]) -> dict[str, Any]:
    matched_items = research_context.get("matched_items") if isinstance(research_context.get("matched_items"), list) else []
    return {
        "source": research_context.get("source"),
        "status": research_context.get("status"),
        "council_id": research_context.get("council_id"),
        "pipeline_run_id": research_context.get("pipeline_run_id"),
        "policy_gate": research_context.get("policy_gate", {}),
        "matched_items_count": len(matched_items),
        "matched_items": matched_items[:5],
    }


def _fundamental_research_confirmed(research_context: dict[str, Any]) -> bool:
    confirmed_statuses = {"passed", "confirmed", "ready", "approved", "watch-approved", "active-watch"}
    status = str(research_context.get("status") or "").lower()
    matched_items = research_context.get("matched_items") if isinstance(research_context.get("matched_items"), list) else []
    item_statuses = {
        str(item.get("status") or "").lower()
        for item in matched_items
        if isinstance(item, dict) and item.get("status")
    }
    return status in confirmed_statuses and (not item_statuses or item_statuses <= confirmed_statuses)


def _cross_venue_market_confirmations(report: dict[str, Any]) -> dict[str, dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for item in report.get("items", []):
        if not isinstance(item, dict) or item.get("status") != "ok":
            continue
        advice = item.get("advice") if isinstance(item.get("advice"), dict) else {}
        quote = item.get("quote") if isinstance(item.get("quote"), dict) else {}
        action = str(advice.get("action") or "").upper()
        confidence = _float(advice.get("confidence"), 0.0)
        price = _float(quote.get("last_price"), 0.0)
        provider = str(advice.get("provider") or item.get("provider") or "")
        symbol = str(advice.get("symbol") or item.get("symbol") or "")
        normalized_symbol = _normalize_symbol(advice.get("normalized_symbol") or quote.get("normalized_symbol") or symbol)
        if not normalized_symbol or not provider or action not in {"BUY", "SELL"} or confidence <= 0 or price <= 0:
            continue
        grouped.setdefault(normalized_symbol, []).append(
            {
                "provider": provider,
                "market_type": str(advice.get("market_type") or item.get("market_type") or ""),
                "symbol": symbol,
                "action": action,
                "confidence": confidence,
                "last_price": price,
                "captured_at": quote.get("captured_at"),
            }
        )

    confirmations: dict[str, dict[str, Any]] = {}
    for normalized_symbol, observations in grouped.items():
        best_by_provider: dict[str, dict[str, Any]] = {}
        for observation in observations:
            current = best_by_provider.get(observation["provider"])
            if current is None or observation["confidence"] > current["confidence"]:
                best_by_provider[observation["provider"]] = observation
        distinct = list(best_by_provider.values())
        actions = {observation["action"] for observation in distinct}
        prices = [observation["last_price"] for observation in distinct]
        average_price = sum(prices) / len(prices) if prices else 0.0
        divergence_pct = ((max(prices) - min(prices)) / average_price * 100.0) if average_price > 0 else 100.0
        minimum_confidence = min((observation["confidence"] for observation in distinct), default=0.0)
        if (
            len(distinct) < MARKET_CONFIRMATION_MIN_PROVIDERS
            or len(actions) != 1
            or minimum_confidence < MARKET_CONFIRMATION_MIN_CONFIDENCE
            or divergence_pct > MARKET_CONFIRMATION_MAX_PRICE_DIVERGENCE_PCT
        ):
            continue
        confirmations[normalized_symbol] = {
            "valid": True,
            "normalized_symbol": normalized_symbol,
            "action": distinct[0]["action"],
            "provider_count": len(distinct),
            "providers": sorted(best_by_provider),
            "minimum_confidence": round(minimum_confidence, 4),
            "maximum_price_divergence_pct": round(divergence_pct, 6),
            "observations": distinct,
            "policy": {
                "minimum_providers": MARKET_CONFIRMATION_MIN_PROVIDERS,
                "minimum_confidence": MARKET_CONFIRMATION_MIN_CONFIDENCE,
                "maximum_price_divergence_pct": MARKET_CONFIRMATION_MAX_PRICE_DIVERGENCE_PCT,
            },
        }
    return confirmations


def _impact_assets(item: dict[str, Any]) -> list[str]:
    assets = []
    raw_assets = item.get("impact_assets")
    if isinstance(raw_assets, list):
        assets.extend(str(asset).strip().upper() for asset in raw_assets if str(asset).strip())
    event_key = str(item.get("event_key") or "").upper()
    for token in ("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA"):
        if token in event_key and token not in assets:
            assets.append(token)
    return assets


def _verified_instruments(report: dict[str, Any]) -> list[dict[str, Any]]:
    config = report.get("config") if isinstance(report.get("config"), dict) else {}
    instruments = config.get("instruments") if isinstance(config.get("instruments"), list) else []
    return [instrument for instrument in instruments if isinstance(instrument, dict) and instrument.get("instrument_id")]


def _instruments_for_asset(asset: str, instruments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    clean = _normalize_symbol(asset)
    return [
        instrument
        for instrument in instruments
        if clean
        and clean
        in {
            _normalize_symbol(instrument.get("symbol")),
            _normalize_symbol(instrument.get("normalized_symbol")),
            _normalize_symbol(instrument.get("base_asset")),
        }
    ]


def _normalize_symbol(value: Any) -> str:
    return "".join(character for character in str(value or "").upper() if character.isalnum())


def _evidence_refs(research_context: dict[str, Any], provider: str, symbol: str) -> list[str]:
    refs = []
    council_id = research_context.get("council_id")
    if council_id:
        refs.append(f"council:{council_id}")
    pipeline_run_id = research_context.get("pipeline_run_id")
    if pipeline_run_id:
        refs.append(f"research_pipeline:{pipeline_run_id}")
    matched_items = research_context.get("matched_items")
    if isinstance(matched_items, list):
        for item in matched_items[:5]:
            if isinstance(item, dict):
                for key_name in ("event_key", "event_id", "watch_item_id", "card_id"):
                    value = item.get(key_name)
                    if value:
                        refs.append(f"research:{key_name}:{value}")
                        break
    if provider and symbol:
        refs.append(f"market:{provider}:{symbol}")
    return list(dict.fromkeys(refs))


def _dedupe_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: dict[tuple[str, str, str], dict[str, Any]] = {}
    for candidate in candidates:
        key = (
            str(candidate.get("provider") or "research"),
            str(candidate.get("market_type") or ""),
            str(candidate.get("normalized_symbol") or candidate.get("symbol") or ""),
        )
        existing = result.get(key)
        if existing is None or _float(candidate.get("score"), 0.0) > _float(existing.get("score"), 0.0):
            result[key] = candidate
    return list(result.values())


def _candidate_id(report_id: Any, provider: str, market_type: str, symbol: str, source_key: str) -> str:
    raw = f"{report_id}:{provider}:{market_type}:{symbol}:{source_key}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def _float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default
