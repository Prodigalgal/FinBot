package io.omnnu.finbot.infrastructure.ai.client;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.net.URI;

public record AiRuntimeProfile(
        AiProviderProfileId profileId,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        TokenLimitParameterStyle tokenLimitParameterStyle,
        URI baseUri,
        String apiKey,
        int requestTimeoutSeconds,
        int maximumConcurrentRequests,
        int acquireTimeoutSeconds,
        long configurationVersion) {
}
