from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Any


@dataclass(frozen=True)
class MarketBar:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    mark_price: float | None = None
    funding_rate: float = 0.0

    def __post_init__(self) -> None:
        if self.timestamp.tzinfo is None:
            raise ValueError("MarketBar.timestamp 必须包含时区")
        prices = (self.open, self.high, self.low, self.close)
        if any(value <= 0 for value in prices):
            raise ValueError("OHLC 价格必须为正数")
        if self.high < max(self.open, self.close, self.low):
            raise ValueError("high 不能低于 open/close/low")
        if self.low > min(self.open, self.close, self.high):
            raise ValueError("low 不能高于 open/close/high")
        if self.mark_price is not None and self.mark_price <= 0:
            raise ValueError("mark_price 必须为正数")


@dataclass(frozen=True)
class BacktestPosition:
    side: str
    quantity: float
    leverage: float
    stop_price: float | None = None
    take_profit_price: float | None = None
    max_holding_bars: int | None = None
    environment: str = "paper"

    def __post_init__(self) -> None:
        if self.side.upper() not in {"BUY", "SELL"}:
            raise ValueError("side 必须为 BUY 或 SELL")
        if self.quantity <= 0 or self.leverage <= 0:
            raise ValueError("quantity 和 leverage 必须为正数")
        if self.stop_price is not None and self.stop_price <= 0:
            raise ValueError("stop_price 必须为正数")
        if self.take_profit_price is not None and self.take_profit_price <= 0:
            raise ValueError("take_profit_price 必须为正数")
        if self.max_holding_bars is not None and self.max_holding_bars < 1:
            raise ValueError("max_holding_bars 必须大于 0")


@dataclass(frozen=True)
class ExecutionBacktestConfig:
    slippage_rate: float = 0.0005
    conservative_intrabar_collision: bool = True

    def __post_init__(self) -> None:
        if not 0 <= self.slippage_rate < 1:
            raise ValueError("slippage_rate 必须在 [0, 1) 范围内")


@dataclass(frozen=True)
class BacktestTradeResult:
    status: str
    venue: str
    symbol: str
    side: str
    entry_time: str
    exit_time: str
    entry_price: float
    exit_price: float
    quantity: float
    leverage: float
    notional_usdt: float
    initial_margin_usdt: float
    liquidation_price: float
    exit_reason: str
    holding_bars: int
    gross_pnl_usdt: float
    fee_usdt: float
    funding_pnl_usdt: float
    net_pnl_usdt: float
    return_on_margin_pct: float
    max_favorable_excursion_pct: float
    max_adverse_excursion_pct: float
    events: tuple[dict[str, Any], ...]
    methodology: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["events"] = list(self.events)
        return payload

