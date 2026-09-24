package io.omnnu.finbot.domain.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.risk.RealWorldMarketModel;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RealWorldOrderMatcherTest {
    private final RealWorldOrderMatcher matcher = new RealWorldOrderMatcher();
    private final RiskPolicy policy = new RiskPolicy(
            "matcher-test-v1",
            true,
            new BigDecimal("0.65"),
            new BigDecimal("500"),
            new BigDecimal("100000"),
            new BigDecimal("10"),
            new BigDecimal("20"),
            5,
            new BigDecimal("0.05"),
            new BigDecimal("0.0006"), // 0.06% Taker fee
            new BigDecimal("0.0005"), // 0.05% base slippage
            new BigDecimal("0.002"));

    @Test
    void marketOrderFillsWithNonlinearSlippageImpact() {
        var smallOrder = matcher.matchMarketOrder(
                DirectionalAction.BUY,
                new BigDecimal("1"),
                BigDecimal.ONE,
                new BigDecimal("50000"),
                new BigDecimal("50010"),
                policy,
                RealWorldMarketModel.standardPerpetual());

        assertTrue(smallOrder.filled());
        // For 50010 USDT notional on 500k depth, impact is small (~0.05% slippage)
        assertTrue(smallOrder.filledPrice().compareTo(new BigDecimal("50010")) > 0);
        assertTrue(smallOrder.effectiveSlippageRate().compareTo(new BigDecimal("0.0005")) >= 0);
        assertEquals(new BigDecimal("0.0006"), smallOrder.effectiveFeeRate());

        // Now test larger order with significant orderbook impact
        var largeOrder = matcher.matchMarketOrder(
                DirectionalAction.BUY,
                new BigDecimal("5"),
                BigDecimal.ONE,
                new BigDecimal("50000"),
                new BigDecimal("50010"),
                policy,
                RealWorldMarketModel.standardPerpetual());

        assertTrue(largeOrder.filled());
        // Slippage rate must strictly increase nonlinearly with higher notional
        assertTrue(largeOrder.effectiveSlippageRate().compareTo(smallOrder.effectiveSlippageRate()) > 0);
    }

    @Test
    void marketOrderBlocksWhenSlippageExceedsMaximumTolerance() {
        var extremeModel = new RealWorldMarketModel(
                new BigDecimal("1000"), // Very shallow depth
                new BigDecimal("0.0001"),
                1,
                new BigDecimal("0.0002"),
                new BigDecimal("0.01")); // Max 1% slippage

        var blockedOrder = matcher.matchMarketOrder(
                DirectionalAction.BUY,
                new BigDecimal("1"),
                BigDecimal.ONE,
                new BigDecimal("50000"),
                new BigDecimal("50010"),
                policy,
                extremeModel);

        assertFalse(blockedOrder.filled());
        assertTrue(blockedOrder.matchReason().contains("滑点超过最大允许阈值"));
    }

    @Test
    void limitOrderFillsWhenCrossedByMarketPrice() {
        // Buy limit order at 49,900 when bid=50,000, ask=50,010.
        // If subsequent low drops to 49,850, it crosses 49,900 and fills at limit price with Maker fee.
        var filled = matcher.matchLimitOrder(
                DirectionalAction.BUY,
                new BigDecimal("2"),
                BigDecimal.ONE,
                new BigDecimal("49900"),
                new BigDecimal("50000"),
                new BigDecimal("50010"),
                new BigDecimal("49850"),
                policy,
                RealWorldMarketModel.standardPerpetual());

        assertTrue(filled.filled());
        assertEquals(0, new BigDecimal("49900").compareTo(filled.filledPrice()));
        assertEquals(0, new BigDecimal("0.0002").compareTo(filled.effectiveFeeRate())); // Maker fee
        assertEquals(0, BigDecimal.ZERO.compareTo(filled.effectiveSlippageRate()));
        assertTrue(filled.matchReason().contains("深度穿越"));
    }

    @Test
    void limitOrderRemainsUnfilledWhenNotCrossed() {
        // Buy limit order at 49,900 when bid=50,000. Subsequent low only reaches 49,950 (didn't reach 49,900).
        var unfilled = matcher.matchLimitOrder(
                DirectionalAction.BUY,
                new BigDecimal("2"),
                BigDecimal.ONE,
                new BigDecimal("49900"),
                new BigDecimal("50000"),
                new BigDecimal("50010"),
                new BigDecimal("49950"),
                policy,
                RealWorldMarketModel.standardPerpetual());

        assertFalse(unfilled.filled());
        assertTrue(unfilled.matchReason().contains("未能向下穿越买单限价"));
    }

    @Test
    void aggressiveLimitOrderCrossesSpreadAndExecutesAsMarket() {
        // Buy limit order placed at 50,050 (above ask of 50,010), immediately aggressive Taker execution
        var takerFill = matcher.matchLimitOrder(
                DirectionalAction.BUY,
                new BigDecimal("1"),
                BigDecimal.ONE,
                new BigDecimal("50050"),
                new BigDecimal("50000"),
                new BigDecimal("50010"),
                new BigDecimal("50010"),
                policy,
                RealWorldMarketModel.standardPerpetual());

        assertTrue(takerFill.filled());
        assertEquals(new BigDecimal("0.0006"), takerFill.effectiveFeeRate()); // Taker fee
        assertTrue(takerFill.effectiveSlippageRate().compareTo(BigDecimal.ZERO) > 0);
    }
}
