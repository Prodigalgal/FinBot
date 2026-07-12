from __future__ import annotations

import unittest
from datetime import datetime, timedelta, timezone

from finbot.backtest import (
    BacktestPosition,
    ExecutionBacktestConfig,
    ExecutionBacktestEngine,
    MarketBar,
    summarize_backtests,
)
from finbot.risk.margin import LinearContractRiskSpec


class ExecutionBacktestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.spec = LinearContractRiskSpec(
            venue="gate",
            symbol="BTC_USDT",
            contract_multiplier=0.0001,
            min_quantity=1,
            quantity_step=1,
            min_notional_usdt=1,
            min_leverage=1,
            max_leverage=200,
            leverage_step=1,
            maintenance_margin_rate=0.003,
            taker_fee_rate=0.00075,
        )
        self.start = datetime(2026, 7, 12, tzinfo=timezone.utc)

    def test_short_take_profit_includes_fees_slippage_and_funding(self) -> None:
        bars = [
            self._bar(0, 100, 100.5, 99.5, 100, funding_rate=0.0001),
            self._bar(1, 100, 100.2, 97.5, 98),
        ]
        result = ExecutionBacktestEngine().run(
            spec=self.spec,
            position=BacktestPosition(
                side="SELL",
                quantity=1_000,
                leverage=20,
                stop_price=102,
                take_profit_price=98,
                environment="testnet",
            ),
            bars=bars,
        )

        self.assertEqual(result.exit_reason, "take_profit")
        self.assertGreater(result.gross_pnl_usdt, result.net_pnl_usdt)
        self.assertGreater(result.fee_usdt, 0)
        self.assertGreater(result.funding_pnl_usdt, 0)
        self.assertEqual(result.methodology["intrabar_collision"], "liquidation_then_stop_then_target")

    def test_collision_uses_conservative_stop_before_target(self) -> None:
        result = ExecutionBacktestEngine().run(
            spec=self.spec,
            position=BacktestPosition(
                side="BUY",
                quantity=1_000,
                leverage=20,
                stop_price=98,
                take_profit_price=102,
            ),
            bars=[self._bar(0, 100, 103, 97, 100)],
        )

        self.assertEqual(result.exit_reason, "stop_loss")
        self.assertLess(result.net_pnl_usdt, 0)

    def test_liquidation_precedes_stop_when_stop_is_not_safe(self) -> None:
        with self.assertRaisesRegex(ValueError, "止损"):
            ExecutionBacktestEngine().run(
                spec=self.spec,
                position=BacktestPosition(
                    side="BUY",
                    quantity=1_000,
                    leverage=100,
                    stop_price=98,
                ),
                bars=[self._bar(0, 100, 100, 97, 98)],
            )

    def test_mainnet_and_invalid_quantity_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "paper/testnet/demo"):
            ExecutionBacktestEngine().run(
                spec=self.spec,
                position=BacktestPosition(side="BUY", quantity=1_000, leverage=5, environment="mainnet"),
                bars=[self._bar(0, 100, 101, 99, 100)],
            )
        with self.assertRaisesRegex(ValueError, "数量步长"):
            ExecutionBacktestEngine().run(
                spec=self.spec,
                position=BacktestPosition(side="BUY", quantity=1.5, leverage=5),
                bars=[self._bar(0, 100, 101, 99, 100)],
            )

    def test_summary_reports_net_equity_drawdown_and_costs(self) -> None:
        engine = ExecutionBacktestEngine()
        winner = engine.run(
            spec=self.spec,
            position=BacktestPosition(side="SELL", quantity=1_000, leverage=10, take_profit_price=98),
            bars=[self._bar(0, 100, 100, 97, 98)],
        )
        loser = engine.run(
            spec=self.spec,
            position=BacktestPosition(side="SELL", quantity=1_000, leverage=10, stop_price=102),
            bars=[self._bar(1, 100, 103, 99, 102)],
        )

        summary = summarize_backtests([winner, loser], starting_equity_usdt=1_000)

        self.assertEqual(summary["status"], "ready")
        self.assertEqual(summary["trade_count"], 2)
        self.assertGreater(summary["fees_usdt"], 0)
        self.assertGreater(summary["max_drawdown_pct"], 0)
        self.assertEqual(summary["methodology"]["sharpe"], "per_trade_not_annualized")

    def _bar(
        self,
        offset: int,
        open_price: float,
        high: float,
        low: float,
        close: float,
        funding_rate: float = 0.0,
    ) -> MarketBar:
        return MarketBar(
            timestamp=self.start + timedelta(hours=offset),
            open=open_price,
            high=high,
            low=low,
            close=close,
            funding_rate=funding_rate,
        )


if __name__ == "__main__":
    unittest.main()

