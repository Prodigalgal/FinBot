package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.time.Instant;
import java.util.Objects;

public record AiProviderProfile(
        String profileId,
        String displayName,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        String baseUrl,
        String baseUrlEnv,
        String apiKeyEnv,
        boolean enabled,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        long version,
        Instant updatedAt) {
    public AiProviderProfile {
        profileId = requireText(profileId, "profileId", 80);
        displayName = requireText(displayName, "displayName", 120);
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(reasoningParameterStyle, "reasoningParameterStyle");
        baseUrl = normalizeNullable(baseUrl);
        baseUrlEnv = normalizeNullable(baseUrlEnv);
        apiKeyEnv = requireText(apiKeyEnv, "apiKeyEnv", 120);
        if ((baseUrl == null) == (baseUrlEnv == null)) {
            throw new IllegalArgumentException("exactly one of baseUrl and baseUrlEnv is required");
        }
        if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60) {
            throw new IllegalArgumentException("connectTimeoutSeconds must be between 1 and 60");
        }
        if (requestTimeoutSeconds < 5 || requestTimeoutSeconds > 1800) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be between 5 and 1800");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
