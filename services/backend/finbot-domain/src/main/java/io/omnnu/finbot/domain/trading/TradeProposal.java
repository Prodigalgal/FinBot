package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import java.time.Instant;
import java.util.Objects;

public record TradeProposal(
        TradeProposalId id,
        TradeDecisionId decisionId,
        InstrumentSymbol symbol,
        DirectionalAction action,
        ProposalStatus status,
        Price entryReference,
        Price targetPrice,
        Price invalidationPrice,
        Instant createdAt) {

    public TradeProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(entryReference, "entryReference");
        Objects.requireNonNull(targetPrice, "targetPrice");
        Objects.requireNonNull(invalidationPrice, "invalidationPrice");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static TradeProposal from(
            TradeProposalId proposalId,
            DirectionalTradeDecision decision,
            Instant createdAt) {
        return new TradeProposal(
                proposalId,
                decision.id(),
                decision.symbol(),
                decision.action(),
                ProposalStatus.GENERATED,
                decision.entryReference(),
                decision.targetPrice(),
                decision.invalidationPrice(),
                createdAt);
    }
}
