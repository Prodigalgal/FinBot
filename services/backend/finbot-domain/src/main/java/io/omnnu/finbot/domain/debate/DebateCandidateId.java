package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.shared.DomainText;

public record DebateCandidateId(String value) {
    public DebateCandidateId {
        value = DomainText.identifier(value, "candidate_");
    }
}
