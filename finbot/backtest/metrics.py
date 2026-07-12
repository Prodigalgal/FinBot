from __future__ import annotations

from math import sqrt
from statistics import mean, pstdev
from typing import Any, Iterable

from finbot.backtest.models import BacktestTradeResult


def summarize_backtests(
    results: Iterable[BacktestTradeResult],
    *,
    starting_equity_usdt: float,
) -> dict[str, Any]:
    rows = list(results)
    if starting_equity_usdt <= 0:
        raise ValueError("starting_equity_usdt 必须为正数")
    if not rows:
        return {
            "status": "insufficient_data",
            "trade_count": 0,
            "methodology": {"annualization": "not_available_without_trade_frequency"},
        }
    pnl = [row.net_pnl_usdt for row in rows]
    positive = [value for value in pnl if value > 0]
    negative = [value for value in pnl if value < 0]
    equity = starting_equity_usdt
    peak = equity
    max_drawdown = 0.0
    returns: list[float] = []
    for value in pnl:
        previous = equity
        equity += value
        returns.append(value / previous if previous > 0 else 0.0)
        peak = max(peak, equity)
        if peak > 0:
            max_drawdown = max(max_drawdown, (peak - equity) / peak)
    volatility = pstdev(returns) if len(returns) > 1 else None
    sharpe_per_trade = (
        mean(returns) / volatility * sqrt(len(returns))
        if volatility is not None and volatility > 0
        else None
    )
    return {
        "status": "ready",
        "trade_count": len(rows),
        "win_count": len(positive),
        "loss_count": len(negative),
        "win_rate": _rounded(len(positive) / len(rows)),
        "gross_profit_usdt": _rounded(sum(positive)),
        "gross_loss_usdt": _rounded(sum(negative)),
        "net_pnl_usdt": _rounded(sum(pnl)),
        "ending_equity_usdt": _rounded(equity),
        "return_pct": _rounded((equity / starting_equity_usdt - 1) * 100),
        "max_drawdown_pct": _rounded(max_drawdown * 100),
        "profit_factor": (
            _rounded(sum(positive) / abs(sum(negative))) if negative else None
        ),
        "sharpe_per_trade": _rounded(sharpe_per_trade) if sharpe_per_trade is not None else None,
        "fees_usdt": _rounded(sum(row.fee_usdt for row in rows)),
        "funding_pnl_usdt": _rounded(sum(row.funding_pnl_usdt for row in rows)),
        "exit_reasons": _counts(row.exit_reason for row in rows),
        "methodology": {
            "equity_curve": "sequential_realized_net_pnl",
            "sharpe": "per_trade_not_annualized",
            "annualization": "not_available_without_trade_frequency",
        },
    }


def _counts(values: Iterable[str]) -> dict[str, int]:
    result: dict[str, int] = {}
    for value in values:
        result[value] = result.get(value, 0) + 1
    return result


def _rounded(value: float) -> float:
    return round(float(value), 8)

