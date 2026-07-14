package io.omnnu.finbot.domain.ai;

import io.omnnu.finbot.domain.shared.DomainText;

public record AiInvocationId(String value) {
    public AiInvocationId {
        value = DomainText.identifier(value, "invocation_");
    }
}
