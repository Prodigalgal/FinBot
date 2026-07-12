from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class ExecutionRiskPolicy:
    max_trade_loss_pct: float = 1.0
    max_daily_loss_pct: float = 3.0
    max_drawdown_pct: float = 10.0
    max_consecutive_losses: int = 3
    min_liquidation_distance_pct: float = 2.0
    max_gross_exposure_pct: float = 50.0

    def __post_init__(self) -> None:
        if min(
            self.max_trade_loss_pct,
            self.max_daily_loss_pct,
            self.max_drawdown_pct,
            self.min_liquidation_distance_pct,
            self.max_gross_exposure_pct,
        ) <= 0:
            raise ValueError("风险阈值必须大于 0")
        if self.max_consecutive_losses < 1:
            raise ValueError("max_consecutive_losses 必须大于 0")


@dataclass(frozen=True)
class ExecutionRiskSnapshot:
    current_equity_usdt: float
    peak_equity_usdt: float
    day_start_equity_usdt: float
    realized_pnl_today_usdt: float
    unrealized_pnl_usdt: float
    consecutive_losses: int
    proposed_max_loss_usdt: float
    proposed_gross_exposure_usdt: float
    liquidation_distance_pct: float
    environment: str = "paper"

    def __post_init__(self) -> None:
        if min(self.current_equity_usdt, self.peak_equity_usdt, self.day_start_equity_usdt) <= 0:
            raise ValueError("equity 必须大于 0")
        if self.proposed_max_loss_usdt < 0 or self.proposed_gross_exposure_usdt < 0:
            raise ValueError("proposed loss/exposure 不能为负数")
        if self.consecutive_losses < 0:
            raise ValueError("consecutive_losses 不能为负数")


def evaluate_execution_risk(
    snapshot: ExecutionRiskSnapshot,
    policy: ExecutionRiskPolicy | None = None,
) -> dict[str, Any]:
    limits = policy or ExecutionRiskPolicy()
    environment = snapshot.environment.lower()
    reasons: list[dict[str, Any]] = []
    if environment not in {"paper", "testnet", "demo"}:
        reasons.append(_reason("environment_forbidden", environment, "paper/testnet/demo"))
    trade_loss_pct = snapshot.proposed_max_loss_usdt / snapshot.current_equity_usdt * 100
    daily_loss_usdt = max(0.0, -(snapshot.realized_pnl_today_usdt + snapshot.unrealized_pnl_usdt))
    projected_daily_loss_pct = (daily_loss_usdt + snapshot.proposed_max_loss_usdt) / snapshot.day_start_equity_usdt * 100
    drawdown_pct = max(0.0, (snapshot.peak_equity_usdt - snapshot.current_equity_usdt) / snapshot.peak_equity_usdt * 100)
    gross_exposure_pct = snapshot.proposed_gross_exposure_usdt / snapshot.current_equity_usdt * 100
    if trade_loss_pct > limits.max_trade_loss_pct:
        reasons.append(_reason("max_trade_loss", trade_loss_pct, limits.max_trade_loss_pct))
    if projected_daily_loss_pct > limits.max_daily_loss_pct:
        reasons.append(_reason("max_daily_loss", projected_daily_loss_pct, limits.max_daily_loss_pct))
    if drawdown_pct >= limits.max_drawdown_pct:
        reasons.append(_reason("max_drawdown", drawdown_pct, limits.max_drawdown_pct))
    if snapshot.consecutive_losses >= limits.max_consecutive_losses:
        reasons.append(_reason("consecutive_losses", snapshot.consecutive_losses, limits.max_consecutive_losses))
    if snapshot.liquidation_distance_pct < limits.min_liquidation_distance_pct:
        reasons.append(_reason("liquidation_distance", snapshot.liquidation_distance_pct, limits.min_liquidation_distance_pct))
    if gross_exposure_pct > limits.max_gross_exposure_pct:
        reasons.append(_reason("gross_exposure", gross_exposure_pct, limits.max_gross_exposure_pct))
    return {
        "status": "blocked" if reasons else "passed",
        "reasons": reasons,
        "metrics": {
            "trade_loss_pct": _round(trade_loss_pct),
            "projected_daily_loss_pct": _round(projected_daily_loss_pct),
            "drawdown_pct": _round(drawdown_pct),
            "consecutive_losses": snapshot.consecutive_losses,
            "liquidation_distance_pct": _round(snapshot.liquidation_distance_pct),
            "gross_exposure_pct": _round(gross_exposure_pct),
        },
        "policy": asdict(limits),
        "ai_can_override": False,
    }


def _reason(code: str, actual: Any, limit: Any) -> dict[str, Any]:
    return {"code": code, "actual": actual, "limit": limit}


def _round(value: float) -> float:
    return round(float(value), 8)
