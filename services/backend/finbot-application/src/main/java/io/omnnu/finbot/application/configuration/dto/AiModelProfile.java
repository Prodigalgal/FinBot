package io.omnnu.finbot.application.configuration.dto;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record AiModelProfile(
        String modelProfileId,
        String providerProfileId,
        String modelName,
        ReasoningEffort defaultReasoningEffort,
        ReasoningEffort maximumReasoningEffort,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion,
        boolean enabled,
        long version,
        Instant updatedAt) {
    public AiModelProfile {
        modelProfileId = requireText(modelProfileId, "modelProfileId", 100);
        providerProfileId = requireText(providerProfileId, "providerProfileId", 80);
        modelName = requireText(modelName, "modelName", 160);
        Objects.requireNonNull(defaultReasoningEffort, "defaultReasoningEffort");
        Objects.requireNonNull(maximumReasoningEffort, "maximumReasoningEffort");
        if (!maximumReasoningEffort.supports(defaultReasoningEffort)) {
            throw new IllegalArgumentException("defaultReasoningEffort exceeds model capability");
        }
        inputUsdPerMillion = DecimalValue.nonNegative(inputUsdPerMillion, "inputUsdPerMillion");
        outputUsdPerMillion = DecimalValue.nonNegative(outputUsdPerMillion, "outputUsdPerMillion");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
