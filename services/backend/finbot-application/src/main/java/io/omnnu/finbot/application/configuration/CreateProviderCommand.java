package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;

public record CreateProviderCommand(
        String displayName,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        String baseUrl,
        boolean enabled,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        int maximumConcurrentRequests,
        int acquireTimeoutSeconds) {
}
