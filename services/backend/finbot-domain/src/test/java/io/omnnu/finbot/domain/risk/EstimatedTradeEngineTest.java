package io.omnnu.finbot.domain.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.DirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.trading.TradeProposalId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EstimatedTradeEngineTest {
    private static final Instant NOW = Instant.parse("2026-07-15T03:00:00Z");

    @Test
    void estimatesQuantityMarginAndNetOutcomesWithCosts() {
        var plan = new EstimatedTradeEngine().estimate(
                proposal(DirectionalAction.BUY, "100", "110", "95"),
                new Confidence(new BigDecimal("0.82")),
                instrument(Optional.of(new Price(new BigDecimal("100")))),
                policy("20"));

        assertEquals(EstimatedTradePlanStatus.ESTIMATED, plan.status());
        assertDecimal("0.9", plan.quantity());
        assertDecimal("90", plan.notionalUsdt());
        assertDecimal("18", plan.leverage());
        assertDecimal("5", plan.initialMarginUsdt());
        assertDecimal("8.7921", plan.estimatedProfitUsdt());
        assertDecimal("4.69305", plan.estimatedLossUsdt());
        assertTrue(plan.riskRewardRatio().compareTo(BigDecimal.ONE) > 0);
        assertTrue(plan.estimatedLossUsdt().compareTo(new BigDecimal("5")) <= 0);
    }

    @Test
    void leverageChangesMarginButNotPositionOrProjectedPnl() {
        var proposal = proposal(DirectionalAction.SELL, "100", "90", "105");
        var instrument = instrument(Optional.of(new Price(new BigDecimal("100"))));
        var fiveTimes = new EstimatedTradeEngine().estimate(
                proposal,
                new Confidence(new BigDecimal("0.82")),
                instrument,
                policy("5"));
        var tenTimes = new EstimatedTradeEngine().estimate(
                proposal,
                new Confidence(new BigDecimal("0.82")),
                instrument,
                policy("10"));

        assertDecimal(fiveTimes.quantity(), tenTimes.quantity());
        assertDecimal(fiveTimes.estimatedProfitUsdt(), tenTimes.estimatedProfitUsdt());
        assertDecimal(fiveTimes.estimatedLossUsdt(), tenTimes.estimatedLossUsdt());
        assertDecimal("5", fiveTimes.leverage());
        assertDecimal("10", tenTimes.leverage());
        assertDecimal(
                fiveTimes.initialMarginUsdt(),
                tenTimes.initialMarginUsdt().multiply(BigDecimal.TWO));
    }

    @Test
    void blocksProjectionWithoutLatestMarketPrice() {
        var plan = new EstimatedTradeEngine().estimate(
                proposal(DirectionalAction.BUY, "100", "110", "95"),
                new Confidence(new BigDecimal("0.82")),
                instrument(Optional.empty()),
                policy("20"));

        assertEquals(EstimatedTradePlanStatus.BLOCKED, plan.status());
        assertTrue(plan.reasons().stream().anyMatch(reason -> reason.contains("最新市场价格")));
    }

    private static TradeProposal proposal(
            DirectionalAction action,
            String entry,
            String target,
            String stop) {
        var decision = new DirectionalTradeDecision(
                new TradeDecisionId("decision_projection_test"),
                new InstrumentSymbol("AAPLUSDT"),
                action,
                new Confidence(new BigDecimal("0.82")),
                new Price(new BigDecimal(entry)),
                new Price(new BigDecimal(target)),
                new Price(new BigDecimal(stop)),
                List.of("test"),
                NOW);
        return TradeProposal.from(new TradeProposalId("proposal_projection_test"), decision, NOW);
    }

    private static ProjectionInstrumentSpec instrument(Optional<Price> currentPrice) {
        return new ProjectionInstrumentSpec(
                new InstrumentId("instrument_bybit_aapl_projection"),
                ExchangeVenue.BYBIT,
                new InstrumentSymbol("AAPLUSDT"),
                BigDecimal.ONE,
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("100"),
                currentPrice);
    }

    private static RiskPolicy policy(String preferredLeverage) {
        return new RiskPolicy(
                "projection-test-v1",
                true,
                new BigDecimal("0.65"),
                new BigDecimal("5"),
                new BigDecimal("100"),
                new BigDecimal(preferredLeverage),
                new BigDecimal("100"),
                3,
                new BigDecimal("0.10"),
                new BigDecimal("0.0006"),
                new BigDecimal("0.0005"),
                new BigDecimal("0.002"));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }
}
