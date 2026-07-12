from __future__ import annotations

from dataclasses import asdict, dataclass
from decimal import Decimal, ROUND_FLOOR
from typing import Any


PAPER_ENVIRONMENTS = frozenset({"paper", "testnet", "demo"})
LIVE_ENVIRONMENTS = frozenset({"live", "mainnet", "production"})
SIDES = frozenset({"BUY", "SELL"})


@dataclass(frozen=True)
class LinearContractRiskSpec:
    venue: str
    symbol: str
    contract_multiplier: float
    min_quantity: float
    quantity_step: float
    min_notional_usdt: float
    min_leverage: float
    max_leverage: float
    leverage_step: float
    maintenance_margin_rate: float
    taker_fee_rate: float

    def __post_init__(self) -> None:
        positive = {
            "contract_multiplier": self.contract_multiplier,
            "min_quantity": self.min_quantity,
            "quantity_step": self.quantity_step,
            "min_notional_usdt": self.min_notional_usdt,
            "min_leverage": self.min_leverage,
            "max_leverage": self.max_leverage,
            "leverage_step": self.leverage_step,
        }
        invalid = [name for name, value in positive.items() if _decimal(value) <= 0]
        if invalid:
            raise ValueError("合约规格必须为正数：" + ", ".join(invalid))
        if _decimal(self.max_leverage) < _decimal(self.min_leverage):
            raise ValueError("max_leverage 不能小于 min_leverage")
        for name, value in {
            "maintenance_margin_rate": self.maintenance_margin_rate,
            "taker_fee_rate": self.taker_fee_rate,
        }.items():
            if not Decimal("0") <= _decimal(value) < Decimal("1"):
                raise ValueError(f"{name} 必须在 [0, 1) 范围内")


@dataclass(frozen=True)
class PositionRiskRequest:
    side: str
    entry_price: float
    stop_price: float
    risk_budget_usdt: float
    requested_leverage: float | None = None
    slippage_rate: float = 0.0005
    liquidation_safety_buffer_rate: float = 0.001
    environment: str = "paper"

    def __post_init__(self) -> None:
        if self.side.upper() not in SIDES:
            raise ValueError("side 必须为 BUY 或 SELL")
        if _decimal(self.entry_price) <= 0 or _decimal(self.stop_price) <= 0:
            raise ValueError("entry_price 和 stop_price 必须为正数")
        if _decimal(self.risk_budget_usdt) <= 0:
            raise ValueError("risk_budget_usdt 必须为正数")
        if self.requested_leverage is not None and _decimal(self.requested_leverage) <= 0:
            raise ValueError("requested_leverage 必须为正数")
        for name, value in {
            "slippage_rate": self.slippage_rate,
            "liquidation_safety_buffer_rate": self.liquidation_safety_buffer_rate,
        }.items():
            if not Decimal("0") <= _decimal(value) < Decimal("1"):
                raise ValueError(f"{name} 必须在 [0, 1) 范围内")


@dataclass(frozen=True)
class PositionRiskPlan:
    status: str
    reasons: tuple[str, ...]
    venue: str
    symbol: str
    environment: str
    side: str
    entry_price: float
    stop_price: float
    stop_distance_pct: float
    risk_budget_usdt: float
    requested_leverage: float | None
    max_safe_leverage: float
    effective_leverage: float
    quantity: float
    notional_usdt: float
    initial_margin_usdt: float
    estimated_entry_fee_usdt: float
    estimated_exit_fee_usdt: float
    estimated_slippage_usdt: float
    estimated_max_loss_usdt: float
    liquidation_distance_pct: float
    approximate_liquidation_price: float
    methodology: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["reasons"] = list(self.reasons)
        return payload


class MarginRiskEngine:
    def plan(
        self,
        spec: LinearContractRiskSpec,
        request: PositionRiskRequest,
    ) -> PositionRiskPlan:
        side = request.side.upper()
        environment = request.environment.strip().lower()
        entry = _decimal(request.entry_price)
        stop = _decimal(request.stop_price)
        risk_budget = _decimal(request.risk_budget_usdt)
        fee_rate = _decimal(spec.taker_fee_rate)
        slippage_rate = _decimal(request.slippage_rate)
        maintenance_rate = _decimal(spec.maintenance_margin_rate)
        safety_buffer = _decimal(request.liquidation_safety_buffer_rate)

        stop_distance = _stop_distance(side, entry, stop)
        round_trip_cost_rate = fee_rate * 2 + slippage_rate * 2
        loss_rate_at_stop = stop_distance + round_trip_cost_rate
        raw_notional = risk_budget / loss_rate_at_stop
        quantity = _floor_step(
            raw_notional / (entry * _decimal(spec.contract_multiplier)),
            _decimal(spec.quantity_step),
        )
        notional = quantity * _decimal(spec.contract_multiplier) * entry

        required_liquidation_distance = stop_distance + safety_buffer
        liquidation_deductions = maintenance_rate + fee_rate + slippage_rate
        raw_safe_leverage = Decimal("1") / (
            required_liquidation_distance + liquidation_deductions
        )
        venue_max = _decimal(spec.max_leverage)
        venue_min = _decimal(spec.min_leverage)
        max_safe_leverage = _floor_step(
            min(raw_safe_leverage, venue_max),
            _decimal(spec.leverage_step),
        )
        max_safe_leverage = max(venue_min, max_safe_leverage)
        requested = (
            _decimal(request.requested_leverage)
            if request.requested_leverage is not None
            else max_safe_leverage
        )
        effective_leverage = min(max(requested, venue_min), max_safe_leverage, venue_max)

        reasons: list[str] = []
        if environment in LIVE_ENVIRONMENTS or environment not in PAPER_ENVIRONMENTS:
            reasons.append("极限杠杆规划只允许 paper/testnet/demo 环境")
        if requested > venue_max:
            reasons.append(
                f"请求杠杆 {float(requested):g}X 超过交易所规格上限 {float(venue_max):g}X"
            )
        if requested > max_safe_leverage:
            reasons.append(
                f"请求杠杆 {float(requested):g}X 无法保证止损先于估算强平，安全上限为 {float(max_safe_leverage):g}X"
            )
        if requested < venue_min:
            reasons.append(
                f"请求杠杆 {float(requested):g}X 低于交易所规格下限 {float(venue_min):g}X"
            )
        if quantity < _decimal(spec.min_quantity):
            reasons.append("风险预算对应数量低于交易所最小下单数量")
        if notional < _decimal(spec.min_notional_usdt):
            reasons.append("风险预算对应名义价值低于交易所最小名义价值")

        liquidation_distance = max(
            Decimal("0"),
            Decimal("1") / effective_leverage - liquidation_deductions,
        )
        liquidation_price = (
            entry * (Decimal("1") - liquidation_distance)
            if side == "BUY"
            else entry * (Decimal("1") + liquidation_distance)
        )
        entry_fee = notional * fee_rate
        exit_fee = notional * fee_rate
        estimated_slippage = notional * slippage_rate * 2
        estimated_loss = notional * stop_distance + entry_fee + exit_fee + estimated_slippage
        initial_margin = notional / effective_leverage

        return PositionRiskPlan(
            status="blocked" if reasons else "passed",
            reasons=tuple(reasons),
            venue=spec.venue,
            symbol=spec.symbol,
            environment=environment,
            side=side,
            entry_price=_float(entry),
            stop_price=_float(stop),
            stop_distance_pct=_percent(stop_distance),
            risk_budget_usdt=_float(risk_budget),
            requested_leverage=_float(requested) if request.requested_leverage is not None else None,
            max_safe_leverage=_float(max_safe_leverage),
            effective_leverage=_float(effective_leverage),
            quantity=_float(quantity),
            notional_usdt=_float(notional),
            initial_margin_usdt=_float(initial_margin),
            estimated_entry_fee_usdt=_float(entry_fee),
            estimated_exit_fee_usdt=_float(exit_fee),
            estimated_slippage_usdt=_float(estimated_slippage),
            estimated_max_loss_usdt=_float(estimated_loss),
            liquidation_distance_pct=_percent(liquidation_distance),
            approximate_liquidation_price=_float(liquidation_price),
            methodology={
                "contract_type": "isolated_linear",
                "quantity_rounding": "floor_to_exchange_step",
                "fees": "taker_fee_on_entry_and_exit",
                "slippage": "symmetric_entry_and_exit_estimate",
                "liquidation": "approximation_not_exchange_quote",
                "path_risk_included": False,
                "ai_can_override": False,
            },
        )


def approximate_liquidation_distance_rate(
    spec: LinearContractRiskSpec,
    leverage: float,
    slippage_rate: float = 0.0005,
) -> float:
    leverage_value = _decimal(leverage)
    if leverage_value <= 0:
        raise ValueError("leverage 必须为正数")
    distance = (
        Decimal("1") / leverage_value
        - _decimal(spec.maintenance_margin_rate)
        - _decimal(spec.taker_fee_rate)
        - _decimal(slippage_rate)
    )
    return _float(max(Decimal("0"), distance))


def _stop_distance(side: str, entry: Decimal, stop: Decimal) -> Decimal:
    if side == "BUY":
        if stop >= entry:
            raise ValueError("BUY 的 stop_price 必须低于 entry_price")
        return (entry - stop) / entry
    if stop <= entry:
        raise ValueError("SELL 的 stop_price 必须高于 entry_price")
    return (stop - entry) / entry


def _floor_step(value: Decimal, step: Decimal) -> Decimal:
    return (value / step).to_integral_value(rounding=ROUND_FLOOR) * step


def _decimal(value: Any) -> Decimal:
    return Decimal(str(value))


def _float(value: Decimal) -> float:
    return float(value.quantize(Decimal("0.00000001")))


def _percent(value: Decimal) -> float:
    return float((value * 100).quantize(Decimal("0.000001")))

