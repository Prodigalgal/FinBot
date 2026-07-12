from __future__ import annotations

import unittest

from finbot.risk.margin import (
    LinearContractRiskSpec,
    MarginRiskEngine,
    PositionRiskRequest,
    approximate_liquidation_distance_rate,
)


class MarginRiskTests(unittest.TestCase):
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

    def test_risk_budget_derives_notional_and_safe_leverage(self) -> None:
        plan = MarginRiskEngine().plan(
            self.spec,
            PositionRiskRequest(
                side="SELL",
                entry_price=63_900,
                stop_price=64_539,
                risk_budget_usdt=10,
                requested_leverage=50,
                environment="testnet",
            ),
        )

        self.assertEqual(plan.status, "passed")
        self.assertLessEqual(plan.estimated_max_loss_usdt, 10)
        self.assertGreater(plan.notional_usdt, 700)
        self.assertEqual(plan.effective_leverage, 50)
        self.assertGreater(plan.liquidation_distance_pct, plan.stop_distance_pct)
        self.assertFalse(plan.methodology["ai_can_override"])

    def test_500x_is_blocked_by_venue_and_liquidation_distance(self) -> None:
        plan = MarginRiskEngine().plan(
            self.spec,
            PositionRiskRequest(
                side="SELL",
                entry_price=63_900,
                stop_price=64_539,
                risk_budget_usdt=10,
                requested_leverage=500,
                environment="testnet",
            ),
        )

        self.assertEqual(plan.status, "blocked")
        self.assertTrue(any("交易所规格上限" in reason for reason in plan.reasons))
        self.assertTrue(any("止损先于估算强平" in reason for reason in plan.reasons))
        self.assertLess(plan.effective_leverage, 100)

    def test_live_environment_is_always_blocked(self) -> None:
        plan = MarginRiskEngine().plan(
            self.spec,
            PositionRiskRequest(
                side="BUY",
                entry_price=63_900,
                stop_price=63_261,
                risk_budget_usdt=10,
                requested_leverage=20,
                environment="mainnet",
            ),
        )

        self.assertEqual(plan.status, "blocked")
        self.assertIn("极限杠杆规划只允许 paper/testnet/demo 环境", plan.reasons)

    def test_liquidation_distance_accounts_for_maintenance_fee_and_slippage(self) -> None:
        distance = approximate_liquidation_distance_rate(
            self.spec,
            leverage=200,
            slippage_rate=0.0005,
        )

        self.assertAlmostEqual(distance, 0.00075)

    def test_stop_direction_is_validated(self) -> None:
        with self.assertRaisesRegex(ValueError, "BUY"):
            MarginRiskEngine().plan(
                self.spec,
                PositionRiskRequest(
                    side="BUY",
                    entry_price=100,
                    stop_price=101,
                    risk_budget_usdt=1,
                ),
            )


if __name__ == "__main__":
    unittest.main()

