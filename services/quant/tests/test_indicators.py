from __future__ import annotations

from datetime import UTC, datetime, timedelta

from finbot_quant.indicators import indicator_values
from finbot_quant.market_data import Candle, InstrumentSeries
from finbot_quant.models import Exchange, ExchangeEnvironment, Instrument, MarketType


def test_indicator_snapshot_contains_trend_momentum_volatility_and_levels() -> None:
    start = datetime(2026, 1, 1, tzinfo=UTC)
    candles: list[Candle] = []
    previous = 100.0
    for index in range(240):
        close = previous + 0.18 + (index % 11 - 5) * 0.025
        candles.append(Candle(
            timestamp=start + timedelta(hours=index),
            open=previous,
            high=max(previous, close) + 0.8,
            low=min(previous, close) - 0.7,
            close=close,
            volume=1_000 + index * 3,
        ))
        previous = close
    series = InstrumentSeries(
        Instrument(
            Exchange.BYBIT,
            ExchangeEnvironment.LIVE,
            "BTCUSDT",
            MarketType.PERPETUAL,
            "USDT",
        ),
        tuple(candles),
    )

    values = indicator_values(series)

    assert values["sma_trend_state_20_50"] == 1
    assert values["golden_cross_state_50_200"] == 1
    assert values["macd_trend_state"] in {-1, 0, 1}
    assert 0 <= values["rsi_14"] <= 100
    assert values["support_level_20"] < values["last_close"]
    assert values["resistance_level_20"] > values["last_close"]
    assert values["atr_14"] > 0
    assert 0 <= values["bollinger_position"] <= 1
