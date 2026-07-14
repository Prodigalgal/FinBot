from __future__ import annotations

import asyncio
import hashlib
import json
import math
from collections.abc import AsyncIterator
from dataclasses import asdict
from statistics import mean, pstdev

from finbot_quant.market_data import (
    ArtifactLoader,
    InstrumentSeries,
    MarketDataArtifactError,
    parse_market_data,
)
from finbot_quant.models import (
    AcceptedUpdate,
    CompletedUpdate,
    FailedUpdate,
    MetricUnit,
    ParameterScalar,
    ProgressUpdate,
    QuantMetric,
    ResearchErrorCode,
    ResearchJob,
    ResearchKind,
    ResearchStage,
    ResearchUpdate,
)


class DefaultResearchEngine:
    def __init__(self, artifact_loader: ArtifactLoader) -> None:
        self._artifact_loader = artifact_loader

    async def stream(
        self,
        job: ResearchJob,
        cancellation: asyncio.Event,
    ) -> AsyncIterator[ResearchUpdate]:
        fingerprint = _job_fingerprint(job)
        yield AcceptedUpdate("finbot-quant/2.0", fingerprint)
        try:
            _check_cancelled(cancellation)
            yield ProgressUpdate(ResearchStage.LOADING_DATA, 1_500, "Loading verified market data")
            payload = await self._artifact_loader.load(job.market_data)
            market_data = parse_market_data(payload)
            selected = _select_series(job, market_data.series)
            _check_cancelled(cancellation)
            yield ProgressUpdate(ResearchStage.COMPUTING, 4_000, "Computing deterministic metrics")
            metrics, observation_count = _compute(job, selected, cancellation)
            _check_cancelled(cancellation)
            yield ProgressUpdate(ResearchStage.EVALUATING, 7_500, "Evaluating costs and drawdown")
            _require_finite_metrics(metrics)
            yield ProgressUpdate(
                ResearchStage.WRITING_ARTIFACTS,
                9_500,
                "Finalizing reproducible result fingerprint",
            )
            result_fingerprint = _result_fingerprint(fingerprint, metrics, observation_count)
            yield CompletedUpdate(
                metrics=tuple(metrics),
                artifacts=(),
                observation_count=observation_count,
                result_fingerprint=result_fingerprint,
            )
        except MarketDataArtifactError as exc:
            yield FailedUpdate(
                ResearchErrorCode.INPUT_ARTIFACT_UNAVAILABLE,
                str(exc),
                False,
            )
        except InsufficientDataError as exc:
            yield FailedUpdate(ResearchErrorCode.INSUFFICIENT_DATA, str(exc), False)
        except ResearchCancelledError:
            yield FailedUpdate(
                ResearchErrorCode.CANCELLED,
                "Quantitative research was cancelled",
                False,
            )
        except (ArithmeticError, ValueError) as exc:
            yield FailedUpdate(
                ResearchErrorCode.COMPUTATION_FAILED,
                f"Quantitative computation failed: {type(exc).__name__}",
                False,
            )


class InsufficientDataError(ValueError):
    pass


class ResearchCancelledError(RuntimeError):
    pass


def _compute(
    job: ResearchJob,
    series: tuple[InstrumentSeries, ...],
    cancellation: asyncio.Event,
) -> tuple[list[QuantMetric], int]:
    if job.kind is ResearchKind.STATISTICAL_ANALYSIS:
        metrics = _statistical_metrics(series)
    elif job.kind in {ResearchKind.BACKTEST, ResearchKind.SIGNAL_EVALUATION}:
        metrics = _signal_metrics(job, series[0])
    elif job.kind is ResearchKind.PARAMETER_SEARCH:
        metrics = _parameter_search(job, series[0], cancellation)
    elif job.kind is ResearchKind.PORTFOLIO_OPTIMIZATION:
        metrics = _portfolio_metrics(series)
    else:
        raise ValueError(f"unsupported research kind: {job.kind}")
    return metrics, sum(len(value.candles) for value in series)


def _statistical_metrics(series: tuple[InstrumentSeries, ...]) -> list[QuantMetric]:
    all_returns = [_returns(value) for value in series]
    flattened = [item for values in all_returns for item in values]
    if len(flattened) < 2:
        raise InsufficientDataError("At least three aligned closes are required")
    annualized_volatility = pstdev(flattened) * math.sqrt(365 * 24)
    positive_rate = sum(value > 0 for value in flattened) / len(flattened)
    return [
        QuantMetric("mean_period_return", mean(flattened), MetricUnit.RATIO),
        QuantMetric("annualized_volatility", annualized_volatility, MetricUnit.RATIO),
        QuantMetric("positive_period_rate", positive_rate, MetricUnit.RATIO),
        QuantMetric("maximum_drawdown", _maximum_drawdown(flattened), MetricUnit.RATIO),
        QuantMetric("instrument_count", float(len(series)), MetricUnit.COUNT),
    ]


def _signal_metrics(job: ResearchJob, series: InstrumentSeries) -> list[QuantMetric]:
    parameters = _parameters(job)
    fast_window = int(parameters.get("fast_window", 12))
    slow_window = int(parameters.get("slow_window", 48))
    fee_rate = float(parameters.get("fee_rate", 0.0006))
    slippage_rate = float(parameters.get("slippage_rate", 0.0005))
    returns = _strategy_returns(series, fast_window, slow_window, fee_rate, slippage_rate)
    return _performance_metrics(returns)


def _parameter_search(
    job: ResearchJob,
    series: InstrumentSeries,
    cancellation: asyncio.Event,
) -> list[QuantMetric]:
    parameters = _parameters(job)
    fee_rate = float(parameters.get("fee_rate", 0.0006))
    slippage_rate = float(parameters.get("slippage_rate", 0.0005))
    candidates = [(5, 24), (8, 36), (12, 48), (20, 72), (24, 120)]
    scored: list[tuple[float, int, int, list[float]]] = []
    for fast_window, slow_window in candidates:
        _check_cancelled(cancellation)
        if slow_window >= len(series.candles):
            continue
        returns = _strategy_returns(
            series,
            fast_window,
            slow_window,
            fee_rate,
            slippage_rate,
        )
        sharpe = _sharpe(returns)
        scored.append(
            (sharpe if sharpe is not None else -math.inf, fast_window, slow_window, returns)
        )
    if not scored:
        raise InsufficientDataError("No parameter candidate has enough observations")
    _, fast_window, slow_window, returns = max(scored, key=lambda item: item[0])
    return [
        *_performance_metrics(returns),
        QuantMetric("selected_fast_window", float(fast_window), MetricUnit.COUNT),
        QuantMetric("selected_slow_window", float(slow_window), MetricUnit.COUNT),
        QuantMetric("evaluated_parameter_sets", float(len(scored)), MetricUnit.COUNT),
    ]


def _portfolio_metrics(series: tuple[InstrumentSeries, ...]) -> list[QuantMetric]:
    if len(series) < 2:
        raise InsufficientDataError("Portfolio optimization requires at least two instruments")
    return_series = [_returns(value) for value in series]
    minimum = min(len(values) for values in return_series)
    if minimum < 10:
        raise InsufficientDataError("Portfolio optimization requires at least ten aligned returns")
    aligned = [values[-minimum:] for values in return_series]
    volatilities = [pstdev(values) for values in aligned]
    inverse = [1 / max(value, 1e-12) for value in volatilities]
    total = sum(inverse)
    weights = [value / total for value in inverse]
    portfolio = [
        sum(weights[index] * values[row] for index, values in enumerate(aligned))
        for row in range(minimum)
    ]
    concentration = sum(weight * weight for weight in weights)
    return [
        *_performance_metrics(portfolio),
        QuantMetric("portfolio_concentration_hhi", concentration, MetricUnit.RATIO),
        QuantMetric("portfolio_instrument_count", float(len(series)), MetricUnit.COUNT),
        QuantMetric("maximum_weight", max(weights), MetricUnit.RATIO),
    ]


def _strategy_returns(
    series: InstrumentSeries,
    fast_window: int,
    slow_window: int,
    fee_rate: float,
    slippage_rate: float,
) -> list[float]:
    if fast_window < 2 or slow_window <= fast_window:
        raise ValueError("moving-average windows are invalid")
    if not 0 <= fee_rate < 0.1 or not 0 <= slippage_rate < 0.1:
        raise ValueError("fee or slippage rate is invalid")
    closes = [value.close for value in series.candles]
    if len(closes) <= slow_window + 1:
        raise InsufficientDataError("Not enough candles for the configured signal windows")
    raw_returns = [closes[index] / closes[index - 1] - 1 for index in range(1, len(closes))]
    strategy: list[float] = []
    previous_position = 0
    for index in range(slow_window, len(closes) - 1):
        fast = mean(closes[index - fast_window + 1 : index + 1])
        slow = mean(closes[index - slow_window + 1 : index + 1])
        position = 1 if fast > slow else -1
        turnover = abs(position - previous_position)
        cost = turnover * (fee_rate + slippage_rate)
        strategy.append(position * raw_returns[index] - cost)
        previous_position = position
    return strategy


def _performance_metrics(returns: list[float]) -> list[QuantMetric]:
    if len(returns) < 2:
        raise InsufficientDataError("At least two strategy returns are required")
    equity = 1.0
    wins = 0
    for value in returns:
        equity *= 1 + value
        wins += value > 0
    sharpe = _sharpe(returns)
    return [
        QuantMetric("net_return", equity - 1, MetricUnit.RATIO),
        QuantMetric("maximum_drawdown", _maximum_drawdown(returns), MetricUnit.RATIO),
        QuantMetric("win_rate", wins / len(returns), MetricUnit.RATIO),
        QuantMetric("sharpe_ratio", sharpe or 0.0, MetricUnit.RATIO),
        QuantMetric("evaluated_periods", float(len(returns)), MetricUnit.COUNT),
    ]


def _returns(series: InstrumentSeries) -> list[float]:
    closes = [value.close for value in series.candles]
    return [closes[index] / closes[index - 1] - 1 for index in range(1, len(closes))]


def _maximum_drawdown(returns: list[float]) -> float:
    equity = 1.0
    peak = 1.0
    maximum = 0.0
    for value in returns:
        equity *= 1 + value
        peak = max(peak, equity)
        maximum = max(maximum, (peak - equity) / peak)
    return maximum


def _sharpe(returns: list[float]) -> float | None:
    if len(returns) < 2:
        return None
    volatility = pstdev(returns)
    return mean(returns) / volatility * math.sqrt(len(returns)) if volatility > 0 else None


def _select_series(
    job: ResearchJob,
    available: tuple[InstrumentSeries, ...],
) -> tuple[InstrumentSeries, ...]:
    requested = {
        (instrument.exchange, instrument.symbol, instrument.market_type)
        for instrument in job.instruments
    }
    selected = tuple(
        value
        for value in available
        if (
            value.instrument.exchange,
            value.instrument.symbol,
            value.instrument.market_type,
        )
        in requested
    )
    if len(selected) != len(requested):
        raise InsufficientDataError(
            "Market data artifact does not contain every requested instrument"
        )
    return selected


def _parameters(job: ResearchJob) -> dict[str, ParameterScalar]:
    return {value.name: value.value for value in job.parameters}


def _job_fingerprint(job: ResearchJob) -> str:
    canonical = {
        "kind": job.kind,
        "instruments": [asdict(value) for value in job.instruments],
        "start": job.start_inclusive.isoformat(),
        "end": job.end_exclusive.isoformat(),
        "market_data_sha256": job.market_data.sha256_hex,
        "strategy_id": job.strategy_id,
        "strategy_version": job.strategy_version,
        "parameters": [(value.name, str(value.value)) for value in job.parameters],
        "seed": job.deterministic_seed,
    }
    return hashlib.sha256(
        json.dumps(canonical, sort_keys=True, separators=(",", ":"), default=str).encode()
    ).hexdigest()


def _result_fingerprint(
    input_fingerprint: str,
    metrics: list[QuantMetric],
    observation_count: int,
) -> str:
    canonical = (
        input_fingerprint,
        tuple((value.name, round(value.value, 12), value.unit) for value in metrics),
        observation_count,
    )
    return hashlib.sha256(repr(canonical).encode()).hexdigest()


def _require_finite_metrics(metrics: list[QuantMetric]) -> None:
    if not metrics or any(not math.isfinite(value.value) for value in metrics):
        raise ArithmeticError("quantitative metrics must be finite and non-empty")


def _check_cancelled(cancellation: asyncio.Event) -> None:
    if cancellation.is_set():
        raise ResearchCancelledError
