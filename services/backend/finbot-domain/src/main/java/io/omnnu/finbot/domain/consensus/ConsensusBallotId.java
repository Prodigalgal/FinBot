package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.shared.DomainText;

public record ConsensusBallotId(String value) {
    public ConsensusBallotId {
        value = DomainText.identifier(value, "ballot_");
    }
}
