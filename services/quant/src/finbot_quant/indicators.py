from __future__ import annotations

import math
from itertools import pairwise
from statistics import mean, pstdev

from finbot_quant.market_data import InstrumentSeries

INDICATOR_DESCRIPTIONS: dict[str, str] = {
    "last_close": "Latest closing price",
    "sma_20": "20-period simple moving average",
    "sma_50": "50-period simple moving average",
    "sma_trend_state_20_50": "20/50 SMA trend state: 1 bullish, -1 bearish",
    "sma_crossover_event_20_50": "Latest 20/50 SMA crossover: 1 golden, -1 death, 0 none",
    "golden_cross_state_50_200": "Long-term 50/200 SMA state: 1 bullish, -1 bearish",
    "golden_cross_event_50_200": "Latest 50/200 crossover: 1 golden, -1 death, 0 none",
    "macd_line_12_26": "MACD line from EMA 12 and EMA 26",
    "macd_signal_9": "Nine-period MACD signal line",
    "macd_histogram": "MACD momentum histogram",
    "macd_trend_state": "MACD state: 1 bullish, -1 bearish",
    "macd_crossover_event": "Latest MACD signal crossover: 1 bullish, -1 bearish, 0 none",
    "rsi_14": "Wilder RSI over 14 periods",
    "bollinger_middle_20": "20-period Bollinger middle band",
    "bollinger_upper_20": "20-period Bollinger upper band at two deviations",
    "bollinger_lower_20": "20-period Bollinger lower band at two deviations",
    "bollinger_position": "Close position within Bollinger bands from 0 to 1",
    "atr_14": "Wilder average true range over 14 periods",
    "support_level_20": "Lowest low in the latest 20 periods",
    "resistance_level_20": "Highest high in the latest 20 periods",
    "distance_to_support_ratio": "Relative distance from close to rolling support",
    "distance_to_resistance_ratio": "Relative distance from rolling resistance to close",
    "support_trend_slope_ratio": "Normalized regression slope of recent lows",
    "resistance_trend_slope_ratio": "Normalized regression slope of recent highs",
}


def indicator_values(series: InstrumentSeries) -> dict[str, float]:
    candles = series.candles
    closes = [value.close for value in candles]
    result: dict[str, float] = {"last_close": closes[-1]}
    _moving_average_indicators(closes, result)
    _macd_indicators(closes, result)
    if len(closes) >= 15:
        result["rsi_14"] = _rsi(closes, 14)
        result["atr_14"] = _atr(series, 14)
    if len(closes) >= 20:
        sample = closes[-20:]
        middle = mean(sample)
        deviation = pstdev(sample)
        upper = middle + 2 * deviation
        lower = middle - 2 * deviation
        result.update(
            {
                "bollinger_middle_20": middle,
                "bollinger_upper_20": upper,
                "bollinger_lower_20": lower,
                "bollinger_position": (
                    min(1.0, max(0.0, (closes[-1] - lower) / (upper - lower)))
                    if upper > lower
                    else 0.5
                ),
            }
        )
        lows = [value.low for value in candles[-20:]]
        highs = [value.high for value in candles[-20:]]
        support = min(lows)
        resistance = max(highs)
        result.update(
            {
                "support_level_20": support,
                "resistance_level_20": resistance,
                "distance_to_support_ratio": (closes[-1] - support) / closes[-1],
                "distance_to_resistance_ratio": (resistance - closes[-1]) / closes[-1],
                "support_trend_slope_ratio": _regression_slope(lows) / closes[-1],
                "resistance_trend_slope_ratio": _regression_slope(highs) / closes[-1],
            }
        )
    if any(not math.isfinite(value) for value in result.values()):
        raise ArithmeticError("Indicator computation returned a non-finite value")
    return result


def indicator_ids() -> tuple[str, ...]:
    return tuple(INDICATOR_DESCRIPTIONS)


def _moving_average_indicators(closes: list[float], result: dict[str, float]) -> None:
    if len(closes) >= 50:
        short_current = mean(closes[-20:])
        long_current = mean(closes[-50:])
        result.update(
            {
                "sma_20": short_current,
                "sma_50": long_current,
                "sma_trend_state_20_50": _state(short_current - long_current),
            }
        )
        if len(closes) >= 51:
            short_previous = mean(closes[-21:-1])
            long_previous = mean(closes[-51:-1])
            result["sma_crossover_event_20_50"] = _crossover(
                short_previous - long_previous,
                short_current - long_current,
            )
    if len(closes) >= 200:
        short_current = mean(closes[-50:])
        long_current = mean(closes[-200:])
        result.update(
            {
                "golden_cross_state_50_200": _state(short_current - long_current),
            }
        )
        if len(closes) >= 201:
            short_previous = mean(closes[-51:-1])
            long_previous = mean(closes[-201:-1])
            result["golden_cross_event_50_200"] = _crossover(
                short_previous - long_previous,
                short_current - long_current,
            )


def _macd_indicators(closes: list[float], result: dict[str, float]) -> None:
    if len(closes) < 35:
        return
    fast = _ema_series(closes, 12)
    slow = _ema_series(closes, 26)
    macd = [left - right for left, right in zip(fast, slow, strict=True)]
    signal = _ema_series(macd, 9)
    result.update(
        {
            "macd_line_12_26": macd[-1],
            "macd_signal_9": signal[-1],
            "macd_histogram": macd[-1] - signal[-1],
            "macd_trend_state": _state(macd[-1] - signal[-1]),
            "macd_crossover_event": _crossover(
                macd[-2] - signal[-2],
                macd[-1] - signal[-1],
            ),
        }
    )


def _ema_series(values: list[float], period: int) -> list[float]:
    multiplier = 2 / (period + 1)
    result = [values[0]]
    for value in values[1:]:
        result.append(value * multiplier + result[-1] * (1 - multiplier))
    return result


def _rsi(closes: list[float], period: int) -> float:
    changes = [current - previous for previous, current in pairwise(closes)]
    average_gain = mean(max(value, 0.0) for value in changes[:period])
    average_loss = mean(max(-value, 0.0) for value in changes[:period])
    for value in changes[period:]:
        average_gain = (average_gain * (period - 1) + max(value, 0.0)) / period
        average_loss = (average_loss * (period - 1) + max(-value, 0.0)) / period
    if average_loss == 0:
        return 100.0
    return 100.0 - 100.0 / (1 + average_gain / average_loss)


def _atr(series: InstrumentSeries, period: int) -> float:
    candles = series.candles
    ranges = [
        max(
            current.high - current.low,
            abs(current.high - previous.close),
            abs(current.low - previous.close),
        )
        for previous, current in pairwise(candles)
    ]
    average = mean(ranges[:period])
    for value in ranges[period:]:
        average = (average * (period - 1) + value) / period
    return average


def _regression_slope(values: list[float]) -> float:
    center = (len(values) - 1) / 2
    denominator = sum((index - center) ** 2 for index in range(len(values)))
    return sum((index - center) * value for index, value in enumerate(values)) / denominator


def _state(value: float) -> float:
    return 1.0 if value > 0 else -1.0 if value < 0 else 0.0


def _crossover(previous: float, current: float) -> float:
    if previous <= 0 < current:
        return 1.0
    if previous >= 0 > current:
        return -1.0
    return 0.0
