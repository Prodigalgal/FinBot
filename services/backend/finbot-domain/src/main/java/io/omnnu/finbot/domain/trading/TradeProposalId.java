package io.omnnu.finbot.domain.trading;

import io.omnnu.finbot.domain.shared.DomainText;

public record TradeProposalId(String value) {
    public TradeProposalId {
        value = DomainText.identifier(value, "proposal_");
    }
}
