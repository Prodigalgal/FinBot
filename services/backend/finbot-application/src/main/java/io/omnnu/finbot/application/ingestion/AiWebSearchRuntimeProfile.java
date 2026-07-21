package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.net.URI;
import java.util.Objects;

public record AiWebSearchRuntimeProfile(
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        URI baseUri,
        String apiKey,
        int requestTimeoutSeconds,
        int maximumConcurrentRequests,
        int acquireTimeoutSeconds,
        long configurationVersion) {
    public AiWebSearchRuntimeProfile {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(reasoningParameterStyle, "reasoningParameterStyle");
        Objects.requireNonNull(baseUri, "baseUri");
        apiKey = Objects.requireNonNull(apiKey, "apiKey");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("AI web search API key must not be blank");
        }
        if (requestTimeoutSeconds < 5 || requestTimeoutSeconds > 3_600) {
            throw new IllegalArgumentException("AI web search timeout is invalid");
        }
        if (maximumConcurrentRequests < 1 || maximumConcurrentRequests > 32) {
            throw new IllegalArgumentException("AI web search provider concurrency is invalid");
        }
        if (acquireTimeoutSeconds < 5 || acquireTimeoutSeconds > 7_200) {
            throw new IllegalArgumentException("AI web search provider capacity timeout is invalid");
        }
        if (configurationVersion < 0) {
            throw new IllegalArgumentException("AI web search provider configuration version is invalid");
        }
    }
}
