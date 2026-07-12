from __future__ import annotations

import hashlib
import math
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from statistics import mean
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


@dataclass(frozen=True)
class PortfolioRiskConfig:
    candle_interval: str = "1h"
    lookback_points: int = 60
    min_correlation_samples: int = 20
    correlation_threshold: float = 0.75
    fallback_total_exposure_pct: float = 10.0
    execution_providers: tuple[str, ...] = ()
    max_single_product_concentration_pct: float = 35.0
    max_provider_concentration_pct: float = 70.0
    max_correlated_cluster_concentration_pct: float = 60.0
    max_hypothetical_stress_loss_pct: float = 10.0


class PortfolioRiskAnalyzer:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def analyze(
        self,
        *,
        loop_run_id: str,
        recommendations: list[dict[str, Any]],
        config: PortfolioRiskConfig | None = None,
        created_at: datetime | None = None,
    ) -> dict[str, Any]:
        policy = config or PortfolioRiskConfig()
        timestamp = _aware(created_at or datetime.now(timezone.utc)).isoformat()
        risk_report_id = _hash_id("portfolio-risk", loop_run_id, timestamp)
        directional = [
            item for item in recommendations
            if isinstance(item, dict) and str(item.get("action") or "").upper() in {"BUY", "SELL"}
        ]
        exposures = self._build_exposures(directional, policy)
        if not exposures:
            report = {
                "status": "passed",
                "risk_report_id": risk_report_id,
                "loop_run_id": loop_run_id,
                "created_at": timestamp,
                "config": asdict(policy),
                "summary": {
                    "risk_status": "no_directional_exposure",
                    "recommendation_count": len(recommendations),
                    "directional_exposure_count": 0,
                    "risk_gate_reason_count": 0,
                },
                "exposures": [],
                "correlations": {"status": "empty", "pairs": [], "clusters": []},
                "concentration": {},
                "stress_tests": [],
                "risk_gate": _risk_gate([]),
                "methodology": _methodology(policy),
            }
            self.store.insert_portfolio_risk_report(report)
            return report

        return_series = {
            exposure["exposure_id"]: self._return_series(exposure, policy)
            for exposure in exposures
        }
        pairs = _correlation_pairs(exposures, return_series, policy.min_correlation_samples)
        clusters = _correlation_clusters(exposures, pairs, policy.correlation_threshold)
        concentration = _concentration(exposures, clusters, policy.execution_providers)
        stress_tests = _stress_tests(exposures)
        reasons = _risk_reasons(concentration, stress_tests, pairs, policy)
        correlation_sampled_pairs = sum(item["status"] == "available" for item in pairs)
        correlation_status = (
            "available" if correlation_sampled_pairs else "insufficient_data"
        )
        risk_gate = _risk_gate(reasons)
        summary = {
            "risk_status": risk_gate["status"],
            "recommendation_count": len(recommendations),
            "directional_exposure_count": len(exposures),
            "gross_notional_pct": _rounded(sum(item["portfolio_weight_pct"] for item in exposures)),
            "largest_product_concentration_pct": concentration["largest_product_concentration_pct"],
            "largest_correlated_cluster_concentration_pct": concentration["largest_correlated_cluster_concentration_pct"],
            "worst_hypothetical_stress_loss_pct": max(
                (float(item["loss_pct"]) for item in stress_tests),
                default=0.0,
            ),
            "correlation_pair_count": len(pairs),
            "correlation_available_pair_count": correlation_sampled_pairs,
            "risk_gate_reason_count": len(reasons),
        }
        report = {
            "status": "passed",
            "risk_report_id": risk_report_id,
            "loop_run_id": loop_run_id,
            "created_at": timestamp,
            "config": asdict(policy),
            "summary": summary,
            "exposures": exposures,
            "correlations": {
                "status": correlation_status,
                "pairs": pairs,
                "clusters": clusters,
                "data_warning": (
                    None if correlation_status == "available"
                    else "No pair reached the configured aligned-return sample threshold; correlation risk is unknown."
                ),
            },
            "concentration": concentration,
            "stress_tests": stress_tests,
            "risk_gate": risk_gate,
            "methodology": _methodology(policy),
        }
        self.store.insert_portfolio_risk_report(report)
        return report

    def _build_exposures(
        self,
        recommendations: list[dict[str, Any]],
        policy: PortfolioRiskConfig,
    ) -> list[dict[str, Any]]:
        if not recommendations:
            return []
        instrument_rows = [dict(row) for row in self.store.list_venue_instruments(active_only=False)]
        instrument_index = {
            (
                str(row.get("provider") or "").lower(),
                str(row.get("market_type") or "").lower(),
                _normalized_symbol(row.get("normalized_symbol") or row.get("symbol")),
            ): row
            for row in instrument_rows
        }
        configured_weights = [
            _position_weight(item)
            for item in recommendations
        ]
        use_fallback = not any(weight > 0 for weight in configured_weights)
        fallback_weight = policy.fallback_total_exposure_pct / len(recommendations) if use_fallback else 0.0
        rows = []
        for item, configured_weight in zip(recommendations, configured_weights, strict=True):
            provider = str(item.get("provider") or "").lower()
            market_type = str(item.get("market_type") or "spot").lower()
            symbol = _normalized_symbol(item.get("normalized_symbol") or item.get("symbol"))
            instrument = instrument_index.get((provider, market_type, symbol), {})
            portfolio_weight = configured_weight if configured_weight > 0 else fallback_weight
            rows.append(
                {
                    "exposure_id": str(item.get("decision_id") or item.get("candidate_id") or _hash_id(provider, market_type, symbol, item.get("action"))),
                    "decision_id": item.get("decision_id"),
                    "symbol": item.get("symbol") or symbol,
                    "normalized_symbol": symbol,
                    "provider": provider or None,
                    "market_type": market_type,
                    "action": str(item.get("action") or "").upper(),
                    "direction": 1 if str(item.get("action") or "").upper() == "BUY" else -1,
                    "portfolio_weight_pct": _rounded(portfolio_weight),
                    "weight_source": "equal_weight_assumption" if use_fallback else "position_sizing.max_position_notional_pct",
                    "canonical_product_id": instrument.get("product_id"),
                    "base_asset": instrument.get("base_asset"),
                    "asset_class": instrument.get("asset_class"),
                    "confidence": _float(item.get("confidence"), 0.0),
                }
            )
        gross = sum(float(item["portfolio_weight_pct"]) for item in rows)
        for row in rows:
            row["relative_concentration_pct"] = _rounded(
                float(row["portfolio_weight_pct"]) / gross * 100.0 if gross > 0 else 0.0
            )
        return rows

    def _return_series(
        self,
        exposure: dict[str, Any],
        policy: PortfolioRiskConfig,
    ) -> dict[str, float]:
        rows = self.store.list_market_candles(
            provider=exposure.get("provider"),
            market_type=exposure.get("market_type"),
            normalized_symbol=exposure.get("normalized_symbol"),
            interval=policy.candle_interval,
        )
        closes = [
            (str(row["open_time"]), _positive_float(row["close"]))
            for row in rows[-max(2, policy.lookback_points + 1):]
        ]
        clean = [(timestamp, close) for timestamp, close in closes if close is not None]
        returns: dict[str, float] = {}
        for previous, current in zip(clean, clean[1:]):
            if previous[1] > 0:
                returns[current[0]] = current[1] / previous[1] - 1.0
        return returns


def _correlation_pairs(
    exposures: list[dict[str, Any]],
    return_series: dict[str, dict[str, float]],
    min_samples: int,
) -> list[dict[str, Any]]:
    pairs = []
    for index, left in enumerate(exposures):
        for right in exposures[index + 1:]:
            left_values = return_series[left["exposure_id"]]
            right_values = return_series[right["exposure_id"]]
            timestamps = sorted(set(left_values).intersection(right_values))
            raw_correlation = None
            if len(timestamps) >= max(2, min_samples):
                raw_correlation = _pearson(
                    [left_values[timestamp] for timestamp in timestamps],
                    [right_values[timestamp] for timestamp in timestamps],
                )
            effective = (
                raw_correlation * int(left["direction"]) * int(right["direction"])
                if raw_correlation is not None else None
            )
            pairs.append(
                {
                    "left_exposure_id": left["exposure_id"],
                    "right_exposure_id": right["exposure_id"],
                    "left_symbol": left["normalized_symbol"],
                    "right_symbol": right["normalized_symbol"],
                    "sample_count": len(timestamps),
                    "status": "available" if raw_correlation is not None else "insufficient_data",
                    "raw_correlation": _rounded(raw_correlation),
                    "effective_pnl_correlation": _rounded(effective),
                }
            )
    return pairs


def _correlation_clusters(
    exposures: list[dict[str, Any]],
    pairs: list[dict[str, Any]],
    threshold: float,
) -> list[dict[str, Any]]:
    adjacency = {item["exposure_id"]: set() for item in exposures}
    for pair in pairs:
        correlation = pair.get("effective_pnl_correlation")
        if correlation is None or float(correlation) < threshold:
            continue
        left = pair["left_exposure_id"]
        right = pair["right_exposure_id"]
        adjacency[left].add(right)
        adjacency[right].add(left)
    by_id = {item["exposure_id"]: item for item in exposures}
    visited: set[str] = set()
    clusters = []
    for exposure_id in adjacency:
        if exposure_id in visited:
            continue
        pending = [exposure_id]
        members: list[str] = []
        while pending:
            current = pending.pop()
            if current in visited:
                continue
            visited.add(current)
            members.append(current)
            pending.extend(adjacency[current] - visited)
        if len(members) < 2:
            continue
        clusters.append(
            {
                "cluster_id": _hash_id("correlation-cluster", *sorted(members)),
                "exposure_ids": sorted(members),
                "symbols": sorted(str(by_id[item]["normalized_symbol"]) for item in members),
                "relative_concentration_pct": _rounded(
                    sum(float(by_id[item]["relative_concentration_pct"]) for item in members)
                ),
                "portfolio_weight_pct": _rounded(
                    sum(float(by_id[item]["portfolio_weight_pct"]) for item in members)
                ),
            }
        )
    return sorted(clusters, key=lambda item: -float(item["relative_concentration_pct"]))


def _concentration(
    exposures: list[dict[str, Any]],
    clusters: list[dict[str, Any]],
    execution_providers: tuple[str, ...],
) -> dict[str, Any]:
    provider_groups = (
        _execution_provider_concentration(exposures, execution_providers)
        if execution_providers
        else _group_concentration(exposures, "provider")
    )
    groups = {
        "provider": provider_groups,
        "canonical_product": _group_concentration(exposures, "canonical_product_id"),
        "base_asset": _group_concentration(exposures, "base_asset"),
        "asset_class": _group_concentration(exposures, "asset_class"),
    }
    relative_weights = [float(item["relative_concentration_pct"]) / 100.0 for item in exposures]
    return {
        "largest_product_concentration_pct": _rounded(max((item["relative_concentration_pct"] for item in exposures), default=0.0)),
        "largest_provider_concentration_pct": _rounded(max((item["relative_concentration_pct"] for item in groups["provider"]), default=0.0)),
        "largest_correlated_cluster_concentration_pct": _rounded(max((item["relative_concentration_pct"] for item in clusters), default=0.0)),
        "herfindahl_hirschman_index": _rounded(sum(weight * weight for weight in relative_weights)),
        "effective_product_count": _rounded(1.0 / sum(weight * weight for weight in relative_weights)) if relative_weights and sum(weight * weight for weight in relative_weights) > 0 else None,
        "provider_basis": "configured_execution_providers" if execution_providers else "recommendation_source_provider",
        "groups": groups,
    }


def _execution_provider_concentration(
    exposures: list[dict[str, Any]],
    execution_providers: tuple[str, ...],
) -> list[dict[str, Any]]:
    providers = tuple(dict.fromkeys(str(provider).strip().lower() for provider in execution_providers if str(provider).strip()))
    if not providers:
        return _group_concentration(exposures, "provider")
    gross_notional = sum(float(exposure["portfolio_weight_pct"]) for exposure in exposures)
    return [
        {
            "group": provider,
            "relative_concentration_pct": _rounded(100.0 / len(providers)),
            "portfolio_weight_pct": _rounded(gross_notional / len(providers)),
        }
        for provider in providers
    ]


def _group_concentration(exposures: list[dict[str, Any]], key: str) -> list[dict[str, Any]]:
    groups: dict[str, dict[str, float]] = {}
    for exposure in exposures:
        value = str(exposure.get(key) or "unknown")
        group = groups.setdefault(value, {"relative": 0.0, "portfolio": 0.0})
        group["relative"] += float(exposure["relative_concentration_pct"])
        group["portfolio"] += float(exposure["portfolio_weight_pct"])
    return sorted(
        (
            {
                "group": group,
                "relative_concentration_pct": _rounded(values["relative"]),
                "portfolio_weight_pct": _rounded(values["portfolio"]),
            }
            for group, values in groups.items()
        ),
        key=lambda item: (-float(item["relative_concentration_pct"]), str(item["group"])),
    )


def _stress_tests(exposures: list[dict[str, Any]]) -> list[dict[str, Any]]:
    largest = max(exposures, key=lambda item: float(item["portfolio_weight_pct"]))
    scenarios = [
        ("broad_market_down_10", "所有标的价格同时下跌 10%", {item["exposure_id"]: -10.0 for item in exposures}),
        ("broad_market_up_10", "所有标的价格同时上涨 10%", {item["exposure_id"]: 10.0 for item in exposures}),
        (
            "adverse_liquidity_gap_5",
            "每个方向性暴露发生 5% 反向跳空",
            {item["exposure_id"]: -5.0 * int(item["direction"]) for item in exposures},
        ),
        (
            "largest_position_adverse_20",
            "最大名义暴露发生 20% 反向跳空",
            {largest["exposure_id"]: -20.0 * int(largest["direction"])},
        ),
    ]
    result = []
    for scenario_id, description, shocks in scenarios:
        contributions = []
        portfolio_return = 0.0
        for exposure in exposures:
            shock = float(shocks.get(exposure["exposure_id"], 0.0))
            contribution = float(exposure["portfolio_weight_pct"]) / 100.0 * int(exposure["direction"]) * shock
            portfolio_return += contribution
            if shock:
                contributions.append(
                    {
                        "exposure_id": exposure["exposure_id"],
                        "symbol": exposure["normalized_symbol"],
                        "shock_pct": shock,
                        "portfolio_return_contribution_pct": _rounded(contribution),
                    }
                )
        result.append(
            {
                "scenario_id": scenario_id,
                "description": description,
                "hypothetical": True,
                "portfolio_return_pct": _rounded(portfolio_return),
                "loss_pct": _rounded(max(0.0, -portfolio_return)),
                "contributions": contributions,
            }
        )
    return result


def _risk_reasons(
    concentration: dict[str, Any],
    stress_tests: list[dict[str, Any]],
    pairs: list[dict[str, Any]],
    policy: PortfolioRiskConfig,
) -> list[dict[str, Any]]:
    reasons = []
    checks = (
        (
            "single_product_concentration",
            float(concentration["largest_product_concentration_pct"]),
            policy.max_single_product_concentration_pct,
        ),
        (
            "provider_concentration",
            float(concentration["largest_provider_concentration_pct"]),
            policy.max_provider_concentration_pct,
        ),
        (
            "correlated_cluster_concentration",
            float(concentration["largest_correlated_cluster_concentration_pct"]),
            policy.max_correlated_cluster_concentration_pct,
        ),
        (
            "hypothetical_stress_loss",
            max((float(item["loss_pct"]) for item in stress_tests), default=0.0),
            policy.max_hypothetical_stress_loss_pct,
        ),
    )
    for code, actual, threshold in checks:
        if actual > threshold:
            reasons.append({"code": code, "actual": _rounded(actual), "threshold": threshold})
    if pairs and not any(pair["status"] == "available" for pair in pairs):
        reasons.append(
            {
                "code": "correlation_data_insufficient",
                "severity": "warning",
                "actual": max((int(pair["sample_count"]) for pair in pairs), default=0),
                "threshold": policy.min_correlation_samples,
            }
        )
    return reasons


def _risk_gate(reasons: list[dict[str, Any]]) -> dict[str, Any]:
    blocking = [item for item in reasons if item.get("severity") != "warning"]
    return {
        "status": "blocked" if blocking else "warning" if reasons else "passed",
        "reasons": reasons,
        "execution_allowed": False,
        "order_api_allowed": False,
        "human_confirmation_required": True,
        "advisory_only": True,
    }


def _methodology(policy: PortfolioRiskConfig) -> dict[str, Any]:
    return {
        "position_source": "max_position_notional_pct; explicit fallback_total_exposure_pct when all positions are absent",
        "correlation": f"Pearson correlation of timestamp-aligned {policy.candle_interval} close returns",
        "minimum_aligned_samples": policy.min_correlation_samples,
        "provider_concentration_source": (
            "configured_execution_providers_equal_split"
            if policy.execution_providers
            else "recommendation_source_provider"
        ),
        "stress_scenarios_are_predictions": False,
        "private_account_data_used": False,
        "execution_allowed": False,
    }


def _position_weight(item: dict[str, Any]) -> float:
    sizing = item.get("position_sizing") if isinstance(item.get("position_sizing"), dict) else {}
    return max(0.0, _float(sizing.get("max_position_notional_pct"), 0.0))


def _pearson(left: list[float], right: list[float]) -> float | None:
    if len(left) != len(right) or len(left) < 2:
        return None
    left_mean = mean(left)
    right_mean = mean(right)
    numerator = sum((x - left_mean) * (y - right_mean) for x, y in zip(left, right, strict=True))
    left_sum = sum((x - left_mean) ** 2 for x in left)
    right_sum = sum((y - right_mean) ** 2 for y in right)
    denominator = math.sqrt(left_sum * right_sum)
    if denominator <= 0:
        return None
    return max(-1.0, min(1.0, numerator / denominator))


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


def _rounded(value: float | None) -> float | None:
    return None if value is None else round(float(value), 6)


def _aware(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def _hash_id(*parts: Any) -> str:
    payload = ":".join(str(part) for part in parts)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]
