package io.omnnu.finbot.domain.identity;

import io.omnnu.finbot.domain.shared.DomainText;

public record AdminSessionId(String value) {
    public AdminSessionId {
        value = DomainText.identifier(value, "session_");
    }
}
