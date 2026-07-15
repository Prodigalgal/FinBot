package io.omnnu.finbot.application.quant;

import io.omnnu.finbot.application.trading.TradeAutomationConfigurationRepository;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.risk.EstimatedTradeEngine;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.domain.risk.ProjectionInstrumentSpec;
import io.omnnu.finbot.domain.risk.RiskInstrumentSpec;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.ProposalStatus;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.trading.TradeProposalId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public final class TradeRiskPreviewService implements TradeRiskPreviewUseCase {
    private final TradeAutomationConfigurationRepository configuration;
    private final MarginRiskEngine marginRiskEngine;
    private final EstimatedTradeEngine estimatedTradeEngine;
    private final Clock clock;

    public TradeRiskPreviewService(
            TradeAutomationConfigurationRepository configuration,
            MarginRiskEngine marginRiskEngine,
            EstimatedTradeEngine estimatedTradeEngine,
            Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.marginRiskEngine = Objects.requireNonNull(marginRiskEngine, "marginRiskEngine");
        this.estimatedTradeEngine = Objects.requireNonNull(estimatedTradeEngine, "estimatedTradeEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TradeRiskPreview preview(TradeRiskPreviewCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        var proposal = new TradeProposal(
                new TradeProposalId("proposal_preview_default"),
                new TradeDecisionId("decision_preview_default"),
                new InstrumentSymbol(command.symbol()),
                command.action(),
                ProposalStatus.GENERATED,
                new Price(command.entryPrice()),
                new Price(command.targetPrice()),
                new Price(command.stopPrice()),
                now);
        var confidence = new Confidence(command.confidence());
        var policy = configuration.snapshot().activeRiskPolicy();
        if (command.executionEnabled()) {
            var plan = marginRiskEngine.assess(
                    proposal,
                    confidence,
                    new RiskInstrumentSpec(
                            new InstrumentId(command.instrumentId()),
                            new ExchangeAccountId(command.accountId()),
                            command.exchange(),
                            command.environment(),
                            new InstrumentSymbol(command.symbol()),
                            command.contractSize(),
                            command.quantityStep(),
                            command.minimumQuantity(),
                            command.venueMaximumLeverage(),
                            new Price(command.currentPrice()),
                            command.openPositionCount()),
                    policy);
            return new TradeRiskPreview(
                    "EXECUTION",
                    plan.status().name(),
                    plan.reasons(),
                    plan.quantity(),
                    plan.notionalUsdt(),
                    plan.leverage(),
                    plan.initialMarginUsdt(),
                    plan.estimatedMaximumLossUsdt(),
                    plan.approximateLiquidationPrice(),
                    null,
                    null,
                    policy,
                    now);
        }
        var plan = estimatedTradeEngine.estimate(
                proposal,
                confidence,
                new ProjectionInstrumentSpec(
                        new InstrumentId(command.instrumentId()),
                        command.exchange(),
                        new InstrumentSymbol(command.symbol()),
                        command.contractSize(),
                        command.quantityStep(),
                        command.minimumQuantity(),
                        command.venueMaximumLeverage(),
                        Optional.of(new Price(command.currentPrice()))),
                policy);
        return new TradeRiskPreview(
                "ESTIMATE",
                plan.status().name(),
                plan.reasons(),
                plan.quantity(),
                plan.notionalUsdt(),
                plan.leverage(),
                plan.initialMarginUsdt(),
                plan.estimatedLossUsdt(),
                null,
                plan.estimatedProfitUsdt(),
                plan.riskRewardRatio(),
                policy,
                now);
    }
}
