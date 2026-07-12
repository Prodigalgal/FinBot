from __future__ import annotations

from decimal import Decimal

from finbot.backtest.models import (
    BacktestPosition,
    BacktestTradeResult,
    ExecutionBacktestConfig,
    MarketBar,
)
from finbot.risk.margin import (
    LIVE_ENVIRONMENTS,
    PAPER_ENVIRONMENTS,
    LinearContractRiskSpec,
    approximate_liquidation_distance_rate,
)


class ExecutionBacktestEngine:
    def run(
        self,
        *,
        spec: LinearContractRiskSpec,
        position: BacktestPosition,
        bars: list[MarketBar],
        config: ExecutionBacktestConfig | None = None,
    ) -> BacktestTradeResult:
        policy = config or ExecutionBacktestConfig()
        ordered_bars = self._validate(spec, position, bars)
        side = position.side.upper()
        direction = Decimal("1") if side == "BUY" else Decimal("-1")
        quantity = _decimal(position.quantity)
        multiplier = _decimal(spec.contract_multiplier)
        leverage = _decimal(position.leverage)
        fee_rate = _decimal(spec.taker_fee_rate)
        slippage_rate = _decimal(policy.slippage_rate)

        entry_bar = ordered_bars[0]
        entry_reference = _decimal(entry_bar.open)
        entry_price = _apply_entry_slippage(entry_reference, direction, slippage_rate)
        entry_notional = entry_price * quantity * multiplier
        initial_margin = entry_notional / leverage
        liquidation_distance = _decimal(
            approximate_liquidation_distance_rate(spec, position.leverage, policy.slippage_rate)
        )
        if liquidation_distance <= 0:
            raise ValueError("杠杆、维持保证金、手续费和滑点组合没有正向强平缓冲")
        liquidation_price = (
            entry_price * (Decimal("1") - liquidation_distance)
            if side == "BUY"
            else entry_price * (Decimal("1") + liquidation_distance)
        )
        self._validate_levels(position, entry_price, liquidation_price)

        events: list[dict[str, object]] = [
            {
                "type": "entry",
                "timestamp": entry_bar.timestamp.isoformat(),
                "reference_price": float(entry_reference),
                "fill_price": _money(entry_price),
                "fee_usdt": _money(entry_notional * fee_rate),
            }
        ]
        funding_pnl = Decimal("0")
        max_favorable = Decimal("0")
        max_adverse = Decimal("0")
        exit_bar = ordered_bars[-1]
        exit_reference = _decimal(exit_bar.close)
        exit_reason = "end_of_data"
        holding_bars = len(ordered_bars)
        liquidation_exit = False

        for index, bar in enumerate(ordered_bars):
            bar_high = _decimal(bar.high)
            bar_low = _decimal(bar.low)
            favorable, adverse = _excursions(side, entry_price, bar_high, bar_low)
            max_favorable = max(max_favorable, favorable)
            max_adverse = min(max_adverse, adverse)

            if bar.funding_rate:
                cashflow = -direction * entry_notional * _decimal(bar.funding_rate)
                funding_pnl += cashflow
                events.append(
                    {
                        "type": "funding",
                        "timestamp": bar.timestamp.isoformat(),
                        "rate": float(bar.funding_rate),
                        "cashflow_usdt": _money(cashflow),
                    }
                )

            trigger = _exit_trigger(
                side=side,
                bar=bar,
                liquidation_price=liquidation_price,
                stop_price=_optional_decimal(position.stop_price),
                take_profit_price=_optional_decimal(position.take_profit_price),
                conservative=policy.conservative_intrabar_collision,
            )
            if trigger is not None:
                exit_reason, exit_reference = trigger
                exit_bar = bar
                holding_bars = index + 1
                liquidation_exit = exit_reason == "liquidation"
                break
            if position.max_holding_bars is not None and index + 1 >= position.max_holding_bars:
                exit_reason = "max_holding_bars"
                exit_reference = _decimal(bar.close)
                exit_bar = bar
                holding_bars = index + 1
                break

        exit_price = (
            exit_reference
            if liquidation_exit
            else _apply_exit_slippage(exit_reference, direction, slippage_rate)
        )
        exit_notional = exit_price * quantity * multiplier
        gross_pnl = direction * (exit_price - entry_price) * quantity * multiplier
        fees = entry_notional * fee_rate + exit_notional * fee_rate
        net_pnl = gross_pnl + funding_pnl - fees
        events.append(
            {
                "type": "exit",
                "timestamp": exit_bar.timestamp.isoformat(),
                "reason": exit_reason,
                "reference_price": _money(exit_reference),
                "fill_price": _money(exit_price),
                "fee_usdt": _money(exit_notional * fee_rate),
            }
        )

        return BacktestTradeResult(
            status="passed",
            venue=spec.venue,
            symbol=spec.symbol,
            side=side,
            entry_time=entry_bar.timestamp.isoformat(),
            exit_time=exit_bar.timestamp.isoformat(),
            entry_price=_money(entry_price),
            exit_price=_money(exit_price),
            quantity=float(quantity),
            leverage=float(leverage),
            notional_usdt=_money(entry_notional),
            initial_margin_usdt=_money(initial_margin),
            liquidation_price=_money(liquidation_price),
            exit_reason=exit_reason,
            holding_bars=holding_bars,
            gross_pnl_usdt=_money(gross_pnl),
            fee_usdt=_money(fees),
            funding_pnl_usdt=_money(funding_pnl),
            net_pnl_usdt=_money(net_pnl),
            return_on_margin_pct=_percent(net_pnl / initial_margin),
            max_favorable_excursion_pct=_percent(max_favorable),
            max_adverse_excursion_pct=_percent(max_adverse),
            events=tuple(events),
            methodology={
                "market_data": "ordered_ohlc_with_optional_mark_and_funding",
                "fill_model": "bar_trigger_with_symmetric_slippage",
                "fee_model": "taker_fee_entry_and_exit",
                "funding_model": "bar_funding_rate_on_entry_notional",
                "liquidation_model": "isolated_linear_approximation",
                "intrabar_collision": (
                    "liquidation_then_stop_then_target"
                    if policy.conservative_intrabar_collision
                    else "nearest_trigger_to_open"
                ),
                "partial_fills_included": False,
                "order_book_impact_included": False,
            },
        )

    def _validate(
        self,
        spec: LinearContractRiskSpec,
        position: BacktestPosition,
        bars: list[MarketBar],
    ) -> list[MarketBar]:
        environment = position.environment.strip().lower()
        if environment in LIVE_ENVIRONMENTS or environment not in PAPER_ENVIRONMENTS:
            raise ValueError("execution backtest 只允许 paper/testnet/demo 环境")
        if position.leverage < spec.min_leverage or position.leverage > spec.max_leverage:
            raise ValueError("leverage 超出交易所合约规格")
        if position.quantity < spec.min_quantity:
            raise ValueError("quantity 低于交易所最小数量")
        quantity_units = _decimal(position.quantity) / _decimal(spec.quantity_step)
        if quantity_units != quantity_units.to_integral_value():
            raise ValueError("quantity 不符合交易所数量步长")
        if not bars:
            raise ValueError("bars 不能为空")
        ordered = sorted(bars, key=lambda item: item.timestamp)
        timestamps = [bar.timestamp for bar in ordered]
        if len(set(timestamps)) != len(timestamps):
            raise ValueError("bars 不能包含重复 timestamp")
        entry_notional = (
            _decimal(ordered[0].open)
            * _decimal(position.quantity)
            * _decimal(spec.contract_multiplier)
        )
        if entry_notional < _decimal(spec.min_notional_usdt):
            raise ValueError("仓位名义价值低于交易所最小名义价值")
        return ordered

    def _validate_levels(
        self,
        position: BacktestPosition,
        entry_price: Decimal,
        liquidation_price: Decimal,
    ) -> None:
        stop = _optional_decimal(position.stop_price)
        target = _optional_decimal(position.take_profit_price)
        if position.side.upper() == "BUY":
            if stop is not None and not liquidation_price < stop < entry_price:
                raise ValueError("BUY 止损必须位于估算强平价与入场价之间")
            if target is not None and target <= entry_price:
                raise ValueError("BUY 止盈必须高于入场价")
        else:
            if stop is not None and not entry_price < stop < liquidation_price:
                raise ValueError("SELL 止损必须位于入场价与估算强平价之间")
            if target is not None and target >= entry_price:
                raise ValueError("SELL 止盈必须低于入场价")


def _exit_trigger(
    *,
    side: str,
    bar: MarketBar,
    liquidation_price: Decimal,
    stop_price: Decimal | None,
    take_profit_price: Decimal | None,
    conservative: bool,
) -> tuple[str, Decimal] | None:
    high = _decimal(bar.high)
    low = _decimal(bar.low)
    candidates: list[tuple[str, Decimal]] = []
    if side == "BUY":
        if low <= liquidation_price:
            candidates.append(("liquidation", liquidation_price))
        if stop_price is not None and low <= stop_price:
            candidates.append(("stop_loss", stop_price))
        if take_profit_price is not None and high >= take_profit_price:
            candidates.append(("take_profit", take_profit_price))
    else:
        if high >= liquidation_price:
            candidates.append(("liquidation", liquidation_price))
        if stop_price is not None and high >= stop_price:
            candidates.append(("stop_loss", stop_price))
        if take_profit_price is not None and low <= take_profit_price:
            candidates.append(("take_profit", take_profit_price))
    if not candidates:
        return None
    if conservative:
        priority = {"liquidation": 0, "stop_loss": 1, "take_profit": 2}
        return min(candidates, key=lambda item: priority[item[0]])
    open_price = _decimal(bar.open)
    return min(candidates, key=lambda item: abs(item[1] - open_price))


def _apply_entry_slippage(price: Decimal, direction: Decimal, rate: Decimal) -> Decimal:
    return price * (Decimal("1") + direction * rate)


def _apply_exit_slippage(price: Decimal, direction: Decimal, rate: Decimal) -> Decimal:
    return price * (Decimal("1") - direction * rate)


def _excursions(
    side: str,
    entry: Decimal,
    high: Decimal,
    low: Decimal,
) -> tuple[Decimal, Decimal]:
    if side == "BUY":
        return (high - entry) / entry, (low - entry) / entry
    return (entry - low) / entry, (entry - high) / entry


def _optional_decimal(value: float | None) -> Decimal | None:
    return None if value is None else _decimal(value)


def _decimal(value: object) -> Decimal:
    return Decimal(str(value))


def _money(value: Decimal) -> float:
    return float(value.quantize(Decimal("0.00000001")))


def _percent(value: Decimal) -> float:
    return float((value * 100).quantize(Decimal("0.000001")))

