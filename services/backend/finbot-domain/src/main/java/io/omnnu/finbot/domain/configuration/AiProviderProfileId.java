package io.omnnu.finbot.domain.configuration;

import io.omnnu.finbot.domain.shared.DomainText;

public record AiProviderProfileId(String value) {
    public AiProviderProfileId {
        value = DomainText.identifier(value, "provider_");
    }
}
