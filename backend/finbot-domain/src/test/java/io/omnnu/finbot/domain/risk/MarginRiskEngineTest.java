package io.omnnu.finbot.domain.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
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
import org.junit.jupiter.api.Test;

class MarginRiskEngineTest {
    private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");
    private static final RiskPolicy POLICY = new RiskPolicy(
            "paper-default-v1",
            true,
            new BigDecimal("0.65"),
            new BigDecimal("5"),
            new BigDecimal("100"),
            new BigDecimal("20"),
            3,
            new BigDecimal("0.10"),
            new BigDecimal("0.0006"),
            new BigDecimal("0.0005"),
            new BigDecimal("0.002"));

    @Test
    void approvesBoundedTestnetPositionAndKeepsStopBeforeLiquidation() {
        var proposal = proposal(DirectionalAction.BUY, "60000", "63000", "58500");

        var plan = new MarginRiskEngine().assess(
                proposal,
                new Confidence(new BigDecimal("0.82")),
                instrument(ExchangeEnvironment.TESTNET, "60010", 0),
                POLICY);

        assertEquals(RiskAssessmentStatus.APPROVED, plan.status());
        assertTrue(plan.notionalUsdt().compareTo(POLICY.maximumNotionalUsdt()) <= 0);
        assertTrue(plan.estimatedMaximumLossUsdt().compareTo(POLICY.riskBudgetUsdt()) <= 0);
        assertTrue(plan.approximateLiquidationPrice().compareTo(
                proposal.invalidationPrice().value()) < 0);
    }

    @Test
    void blocksContradictoryPricesAndStaleEntryReference() {
        var proposal = proposal(DirectionalAction.BUY, "60000", "58000", "61000");

        var plan = new MarginRiskEngine().assess(
                proposal,
                new Confidence(new BigDecimal("0.90")),
                instrument(ExchangeEnvironment.DEMO, "65000", 0),
                POLICY);

        assertEquals(RiskAssessmentStatus.BLOCKED, plan.status());
        assertTrue(plan.reasons().size() >= 2);
    }

    @Test
    void blocksLowConfidenceOrPortfolioLimit() {
        var plan = new MarginRiskEngine().assess(
                proposal(DirectionalAction.SELL, "60000", "57000", "61500"),
                new Confidence(new BigDecimal("0.50")),
                instrument(ExchangeEnvironment.TESTNET, "60000", 3),
                POLICY);

        assertEquals(RiskAssessmentStatus.BLOCKED, plan.status());
        assertTrue(plan.reasons().stream().anyMatch(reason -> reason.contains("置信度")));
        assertTrue(plan.reasons().stream().anyMatch(reason -> reason.contains("未平仓")));
    }

    private static TradeProposal proposal(
            DirectionalAction action,
            String entry,
            String target,
            String stop) {
        var decision = new DirectionalTradeDecision(
                new TradeDecisionId("decision_risk_test"),
                new InstrumentSymbol("BTC_USDT"),
                action,
                new Confidence(new BigDecimal("0.82")),
                new Price(new BigDecimal(entry)),
                new Price(new BigDecimal(target)),
                new Price(new BigDecimal(stop)),
                List.of("test"),
                NOW);
        return TradeProposal.from(new TradeProposalId("proposal_risk_test"), decision, NOW);
    }

    private static RiskInstrumentSpec instrument(
            ExchangeEnvironment environment,
            String currentPrice,
            int openPositions) {
        return new RiskInstrumentSpec(
                new InstrumentId("instrument_gate_btc_test"),
                new ExchangeAccountId("account_gate_test"),
                ExchangeVenue.GATE,
                environment,
                new InstrumentSymbol("BTC_USDT"),
                new BigDecimal("0.0001"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("100"),
                new Price(new BigDecimal(currentPrice)),
                openPositions);
    }
}
