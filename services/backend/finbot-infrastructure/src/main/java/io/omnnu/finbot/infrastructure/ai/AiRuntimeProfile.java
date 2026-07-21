package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.net.URI;

record AiRuntimeProfile(
        AiProviderProfileId profileId,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        URI baseUri,
        String apiKey,
        int requestTimeoutSeconds,
        int maximumConcurrentRequests,
        int acquireTimeoutSeconds,
        long configurationVersion) {
}
