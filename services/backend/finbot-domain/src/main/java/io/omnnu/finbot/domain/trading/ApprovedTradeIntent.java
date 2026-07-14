package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.market.Quantity;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.risk.RiskAssessmentId;
import io.omnnu.finbot.domain.shared.DomainText;
import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record ApprovedTradeIntent(
        ApprovedTradeIntentId id,
        TradeProposalId proposalId,
        ExchangeAccountId accountId,
        RiskAssessmentId riskAssessmentId,
        InstrumentSymbol symbol,
        DirectionalAction action,
        Quantity quantity,
        BigDecimal leverage,
        Price entryReference,
        Price targetPrice,
        Price invalidationPrice,
        String policyVersion,
        Instant approvedAt) {

    public ApprovedTradeIntent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(riskAssessmentId, "riskAssessmentId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.value().signum() <= 0) {
            throw new IllegalArgumentException("approved quantity must be positive");
        }
        leverage = DecimalValue.positive(leverage, "leverage");
        if (leverage.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("leverage must be at least 1");
        }
        Objects.requireNonNull(entryReference, "entryReference");
        Objects.requireNonNull(targetPrice, "targetPrice");
        Objects.requireNonNull(invalidationPrice, "invalidationPrice");
        policyVersion = DomainText.required(policyVersion, "policyVersion", 80);
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
    }

    public static ApprovedTradeIntent approve(
            ApprovedTradeIntentId intentId,
            TradeProposal proposal,
            ExecutionReview review,
            ExchangeAccountId accountId,
            RiskAssessmentId riskAssessmentId,
            Quantity quantity,
            BigDecimal leverage) {
        if (review.status() != ApprovalStatus.APPROVED) {
            throw new IllegalArgumentException("only an approved execution review can create a trade intent");
        }
        if (proposal.status() != ProposalStatus.GENERATED) {
            throw new IllegalArgumentException("only a current generated proposal can be approved");
        }
        return new ApprovedTradeIntent(
                intentId,
                proposal.id(),
                accountId,
                riskAssessmentId,
                proposal.symbol(),
                proposal.action(),
                quantity,
                leverage,
                proposal.entryReference(),
                proposal.targetPrice(),
                proposal.invalidationPrice(),
                review.policyVersion(),
                review.reviewedAt());
    }
}
