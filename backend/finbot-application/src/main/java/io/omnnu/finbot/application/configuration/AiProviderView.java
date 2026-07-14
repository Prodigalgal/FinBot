package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.time.Instant;

public record AiProviderView(
        String profileId,
        String displayName,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        String baseUrl,
        String baseUrlEnv,
        String apiKeyEnv,
        boolean baseUrlConfigured,
        boolean apiKeyConfigured,
        boolean enabled,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        long version,
        Instant updatedAt) {
}
