from __future__ import annotations

import unittest

from finbot.risk import ExecutionRiskSnapshot, evaluate_execution_risk


class ExecutionRiskLimitsTests(unittest.TestCase):
    def test_all_hard_limits_are_reported_together(self) -> None:
        result = evaluate_execution_risk(
            ExecutionRiskSnapshot(
                current_equity_usdt=800,
                peak_equity_usdt=1000,
                day_start_equity_usdt=900,
                realized_pnl_today_usdt=-20,
                unrealized_pnl_usdt=-10,
                consecutive_losses=3,
                proposed_max_loss_usdt=20,
                proposed_gross_exposure_usdt=600,
                liquidation_distance_pct=1,
                environment="testnet",
            )
        )
        codes = {reason["code"] for reason in result["reasons"]}
        self.assertEqual(result["status"], "blocked")
        self.assertTrue({"max_trade_loss", "max_daily_loss", "max_drawdown", "consecutive_losses", "liquidation_distance", "gross_exposure"}.issubset(codes))
        self.assertFalse(result["ai_can_override"])

    def test_conservative_position_passes(self) -> None:
        result = evaluate_execution_risk(
            ExecutionRiskSnapshot(
                current_equity_usdt=1000,
                peak_equity_usdt=1000,
                day_start_equity_usdt=1000,
                realized_pnl_today_usdt=0,
                unrealized_pnl_usdt=0,
                consecutive_losses=0,
                proposed_max_loss_usdt=5,
                proposed_gross_exposure_usdt=100,
                liquidation_distance_pct=5,
            )
        )
        self.assertEqual(result["status"], "passed")

    def test_mainnet_is_always_blocked(self) -> None:
        snapshot = ExecutionRiskSnapshot(1000, 1000, 1000, 0, 0, 0, 1, 1, 10, "mainnet")
        self.assertEqual(evaluate_execution_risk(snapshot)["reasons"][0]["code"], "environment_forbidden")


if __name__ == "__main__":
    unittest.main()
