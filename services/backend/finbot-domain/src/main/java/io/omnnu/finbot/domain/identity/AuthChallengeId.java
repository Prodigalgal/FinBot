package io.omnnu.finbot.domain.identity;

import io.omnnu.finbot.domain.shared.DomainText;

public record AuthChallengeId(String value) {
    public AuthChallengeId {
        value = DomainText.identifier(value, "challenge_");
    }
}
