package io.omnnu.finbot.domain.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.market.Quantity;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.risk.RiskAssessmentId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TradeTypeSafetyTest {
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    @Test
    void directionalDecisionCanBecomeProposalThenApprovedIntent() {
        var decision = new DirectionalTradeDecision(
                new TradeDecisionId("decision_01j00000"),
                new InstrumentSymbol("BTC_USDT"),
                DirectionalAction.BUY,
                new Confidence(new BigDecimal("0.82")),
                new Price(new BigDecimal("60000")),
                new Price(new BigDecimal("63000")),
                new Price(new BigDecimal("58500")),
                List.of("evidence confirmed"),
                NOW);
        var proposal = TradeProposal.from(new TradeProposalId("proposal_01j00000"), decision, NOW);
        var review = new ExecutionReview(ApprovalStatus.APPROVED, List.of("risk gate passed"), "risk-v2", NOW);

        var intent = ApprovedTradeIntent.approve(
                new ApprovedTradeIntentId("intent_01j00000"),
                proposal,
                review,
                new ExchangeAccountId("account_gate_test"),
                new InstrumentId("instrument_gate_btc_test"),
                ExchangeVenue.GATE,
                ExchangeEnvironment.TESTNET,
                new RiskAssessmentId("assessment_01j00000"),
                Quantity.positive(new BigDecimal("0.001")),
                new BigDecimal("10"));

        assertEquals(DirectionalAction.BUY, intent.action());
        assertEquals(proposal.id(), intent.proposalId());
    }

    @Test
    void rejectedReviewCannotCreateExecutableIntent() {
        var decision = new DirectionalTradeDecision(
                new TradeDecisionId("decision_01j00001"),
                new InstrumentSymbol("ETHUSDT"),
                DirectionalAction.SELL,
                new Confidence(new BigDecimal("0.76")),
                new Price(new BigDecimal("3000")),
                new Price(new BigDecimal("2800")),
                new Price(new BigDecimal("3100")),
                List.of("bearish structure"),
                NOW);
        var proposal = TradeProposal.from(new TradeProposalId("proposal_01j00001"), decision, NOW);
        var review = new ExecutionReview(ApprovalStatus.BLOCKED, List.of("portfolio limit"), "risk-v2", NOW);

        assertThrows(
                IllegalArgumentException.class,
                () -> ApprovedTradeIntent.approve(
                        new ApprovedTradeIntentId("intent_01j00001"),
                        proposal,
                        review,
                        new ExchangeAccountId("account_bybit_test"),
                        new InstrumentId("instrument_bybit_eth_test"),
                        ExchangeVenue.BYBIT,
                        ExchangeEnvironment.DEMO,
                        new RiskAssessmentId("assessment_01j00001"),
                        Quantity.positive(BigDecimal.ONE),
                        new BigDecimal("5")));
    }
}
