from __future__ import annotations

import math
from collections.abc import Callable, Sized
from statistics import mean, pstdev

from finbot_quant.market_data import InstrumentSeries
from finbot_quant.models import ParameterScalar

type StrategyFunction = Callable[[InstrumentSeries, dict[str, ParameterScalar]], list[int]]

STRATEGY_DESCRIPTIONS: dict[str, str] = {
    "moving_average_crossover": "Fast and slow moving-average trend following",
    "breakout": "Rolling high and low breakout continuation",
    "mean_reversion": "Z-score based reversion toward a rolling mean",
    "rsi_momentum": "RSI threshold momentum confirmation",
    "volume_confirmed_trend": "Moving-average trend confirmed by relative volume",
    "multi_strategy_ensemble": "Vote across trend, breakout, reversion, RSI, and volume signals",
}


class StrategyNotFoundError(ValueError):
    pass


class StrategyRegistry:
    def __init__(self, strategies: dict[str, StrategyFunction] | None = None) -> None:
        self._strategies = dict(strategies or default_strategies())

    def positions(
        self,
        strategy_id: str,
        series: InstrumentSeries,
        parameters: dict[str, ParameterScalar],
    ) -> list[int]:
        strategy = self._strategies.get(strategy_id)
        if strategy is None:
            raise StrategyNotFoundError(f"Unsupported strategy: {strategy_id}")
        positions = strategy(series, parameters)
        if len(positions) != len(series.candles) or any(
            value not in {-1, 0, 1} for value in positions
        ):
            raise ArithmeticError("Strategy returned an invalid position series")
        return positions

    def ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._strategies))


def default_strategies() -> dict[str, StrategyFunction]:
    return {
        "moving_average_crossover": moving_average_crossover,
        "breakout": breakout,
        "mean_reversion": mean_reversion,
        "rsi_momentum": rsi_momentum,
        "volume_confirmed_trend": volume_confirmed_trend,
        "multi_strategy_ensemble": multi_strategy_ensemble,
    }


def moving_average_crossover(
    series: InstrumentSeries,
    parameters: dict[str, ParameterScalar],
) -> list[int]:
    fast_window = integer(parameters, "fast_window", 12, minimum=2)
    slow_window = integer(parameters, "slow_window", 48, minimum=3)
    if slow_window <= fast_window:
        raise ValueError("slow_window must be greater than fast_window")
    closes = [candle.close for candle in series.candles]
    require_window(closes, slow_window)
    positions = [0] * len(closes)
    for index in range(slow_window - 1, len(closes)):
        fast = mean(closes[index - fast_window + 1 : index + 1])
        slow = mean(closes[index - slow_window + 1 : index + 1])
        positions[index] = 1 if fast > slow else -1
    return positions


def breakout(
    series: InstrumentSeries,
    parameters: dict[str, ParameterScalar],
) -> list[int]:
    lookback = integer(parameters, "breakout_window", 20, minimum=3)
    require_window(series.candles, lookback + 1)
    positions = [0] * len(series.candles)
    current = 0
    for index in range(lookback, len(series.candles)):
        history = series.candles[index - lookback : index]
        candle = series.candles[index]
        if candle.close > max(value.high for value in history):
            current = 1
        elif candle.close < min(value.low for value in history):
            current = -1
        positions[index] = current
    return positions


def mean_reversion(
    series: InstrumentSeries,
    parameters: dict[str, ParameterScalar],
) -> list[int]:
    window = integer(parameters, "mean_reversion_window", 24, minimum=5)
    entry_z = floating(parameters, "mean_reversion_entry_z", 1.5, minimum=0.1)
    exit_z = floating(parameters, "mean_reversion_exit_z", 0.25, minimum=0.0)
    if exit_z >= entry_z:
        raise ValueError("mean_reversion_exit_z must be below entry threshold")
    closes = [candle.close for candle in series.candles]
    require_window(closes, window + 1)
    positions = [0] * len(closes)
    current = 0
    for index in range(window - 1, len(closes)):
        sample = closes[index - window + 1 : index + 1]
        deviation = pstdev(sample)
        z_score = (closes[index] - mean(sample)) / deviation if deviation > 0 else 0.0
        if z_score >= entry_z:
            current = -1
        elif z_score <= -entry_z:
            current = 1
        elif abs(z_score) <= exit_z:
            current = 0
        positions[index] = current
    return positions


def rsi_momentum(
    series: InstrumentSeries,
    parameters: dict[str, ParameterScalar],
) -> list[int]:
    window = integer(parameters, "rsi_window", 14, minimum=3)
    long_threshold = floating(parameters, "rsi_long_threshold", 55.0, minimum=0.0)
    short_threshold = floating(parameters, "rsi_short_threshold", 45.0, minimum=0.0)
    if not 0 <= short_threshold < long_threshold <= 100:
        raise ValueError("RSI thresholds are invalid")
    closes = [candle.close for candle in series.candles]
    require_window(closes, window + 1)
    positions = [0] * len(closes)
    for index in range(window, len(closes)):
        changes = [
            closes[offset] - closes[offset - 1]
            for offset in range(index - window + 1, index + 1)
        ]
        gains = sum(max(value, 0.0) for value in changes) / window
        losses = sum(max(-value, 0.0) for value in changes) / window
        rsi = 100.0 if losses == 0 else 100.0 - 100.0 / (1.0 + gains / losses)
        positions[index] = 1 if rsi >= long_threshold else -1 if rsi <= short_threshold else 0
    return positions


def volume_confirmed_trend(
    series: InstrumentSeries,
    parameters: dict[str, ParameterScalar],
) -> list[int]:
    fast_window = integer(parameters, "volume_fast_window", 8, minimum=2)
    slow_window = integer(parameters, "volume_slow_window", 36, minimum=3)
    volume_window = integer(parameters, "volume_window", 24, minimum=3)
    volume_multiplier = floating(parameters, "volume_multiplier", 1.05, minimum=0.0)
    required = max(slow_window, volume_window)
    closes = [candle.close for candle in series.candles]
    volumes = [candle.volume for candle in series.candles]
    require_window(closes, required + 1)
    positions = [0] * len(closes)
    for index in range(required - 1, len(closes)):
        fast = mean(closes[index - fast_window + 1 : index + 1])
        slow = mean(closes[index - slow_window + 1 : index + 1])
        average_volume = mean(volumes[index - volume_window + 1 : index + 1])
        if volumes[index] >= average_volume * volume_multiplier:
            positions[index] = 1 if fast > slow else -1
    return positions


def multi_strategy_ensemble(
    series: InstrumentSeries,
    parameters: dict[str, ParameterScalar],
) -> list[int]:
    members = (
        moving_average_crossover(series, parameters),
        breakout(series, parameters),
        mean_reversion(series, parameters),
        rsi_momentum(series, parameters),
        volume_confirmed_trend(series, parameters),
    )
    threshold = integer(parameters, "ensemble_minimum_votes", 2, minimum=1)
    if threshold > len(members):
        raise ValueError("ensemble_minimum_votes exceeds strategy count")
    positions: list[int] = []
    for votes in zip(*members, strict=True):
        score = sum(votes)
        positions.append(1 if score >= threshold else -1 if score <= -threshold else 0)
    return positions


def strategy_returns(
    series: InstrumentSeries,
    positions: list[int],
    *,
    fee_rate: float,
    slippage_rate: float,
) -> list[float]:
    if not 0 <= fee_rate < 0.1 or not 0 <= slippage_rate < 0.1:
        raise ValueError("fee or slippage rate is invalid")
    returns: list[float] = []
    previous_position = 0
    for index in range(1, len(series.candles)):
        position = positions[index - 1]
        candle = series.candles[index]
        raw_return = candle.close / series.candles[index - 1].close - 1.0
        turnover = abs(position - previous_position)
        cost = turnover * (fee_rate + slippage_rate)
        funding = position * candle.funding_rate
        value = position * raw_return - cost - funding
        if not math.isfinite(value) or value <= -1:
            raise ArithmeticError("Strategy return is outside the supported range")
        returns.append(value)
        previous_position = position
    return returns


def integer(
    parameters: dict[str, ParameterScalar],
    name: str,
    default: int,
    *,
    minimum: int,
) -> int:
    value = parameters.get(name, default)
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise ValueError(f"{name} must be numeric")
    result = int(value)
    if result < minimum:
        raise ValueError(f"{name} is below its minimum")
    return result


def floating(
    parameters: dict[str, ParameterScalar],
    name: str,
    default: float,
    *,
    minimum: float,
) -> float:
    value = parameters.get(name, default)
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise ValueError(f"{name} must be numeric")
    result = float(value)
    if not math.isfinite(result) or result < minimum:
        raise ValueError(f"{name} is invalid")
    return result


def require_window(values: Sized, required: int) -> None:
    if len(values) < required:
        raise ValueError(f"Strategy requires at least {required} candles")
