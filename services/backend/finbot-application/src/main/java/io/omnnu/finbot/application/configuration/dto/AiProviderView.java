package io.omnnu.finbot.application.configuration.dto;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.time.Instant;

public record AiProviderView(
        String profileId,
        String displayName,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        String baseUrl,
        boolean baseUrlConfigured,
        boolean apiKeyConfigured,
        RuntimeSecretSource credentialSource,
        String credentialFingerprint,
        long credentialVersion,
        Instant credentialUpdatedAt,
        boolean enabled,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        int maximumConcurrentRequests,
        int acquireTimeoutSeconds,
        long workflowNodeUsageCount,
        long roleTemplateUsageCount,
        long executionStageUsageCount,
        long totalUsageCount,
        long version,
        Instant updatedAt) {
}
