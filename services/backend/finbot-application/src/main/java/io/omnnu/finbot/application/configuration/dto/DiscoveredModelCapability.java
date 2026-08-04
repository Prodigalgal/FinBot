package io.omnnu.finbot.application.configuration.dto;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.util.Objects;
import java.util.Set;

public record DiscoveredModelCapability(
        String modelName,
        ModelAvailability availability,
        Set<AiProtocol> supportedProtocols,
        ReasoningEffort maximumReasoningEffort,
        Set<TokenLimitParameterStyle> tokenLimitParameterStyles,
        CapabilitySupport streaming,
        CapabilitySupport tools,
        CapabilitySupport multimodal,
        Long maximumContextTokens,
        Long maximumInputTokens,
        Long maximumOutputTokens,
        ModelCapabilitySources sources) {
    public DiscoveredModelCapability {
        modelName = Objects.requireNonNull(modelName, "modelName").strip();
        if (modelName.isEmpty() || modelName.length() > 160) {
            throw new IllegalArgumentException("modelName is invalid");
        }
        Objects.requireNonNull(availability, "availability");
        supportedProtocols = Set.copyOf(Objects.requireNonNull(supportedProtocols, "supportedProtocols"));
        tokenLimitParameterStyles = Set.copyOf(
                Objects.requireNonNull(tokenLimitParameterStyles, "tokenLimitParameterStyles"));
        Objects.requireNonNull(streaming, "streaming");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(multimodal, "multimodal");
        requirePositive(maximumContextTokens, "maximumContextTokens");
        requirePositive(maximumInputTokens, "maximumInputTokens");
        requirePositive(maximumOutputTokens, "maximumOutputTokens");
        Objects.requireNonNull(sources, "sources");
    }

    public static DiscoveredModelCapability unknown(String modelName) {
        return new DiscoveredModelCapability(
                modelName,
                ModelAvailability.UNKNOWN,
                Set.of(),
                null,
                Set.of(),
                CapabilitySupport.UNKNOWN,
                CapabilitySupport.UNKNOWN,
                CapabilitySupport.UNKNOWN,
                null,
                null,
                null,
                ModelCapabilitySources.UNKNOWN);
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
