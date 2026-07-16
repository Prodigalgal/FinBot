package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;

public record UpdateProviderCommand(
        String profileId,
        String displayName,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        String baseUrl,
        boolean enabled,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        long expectedVersion) {
}
