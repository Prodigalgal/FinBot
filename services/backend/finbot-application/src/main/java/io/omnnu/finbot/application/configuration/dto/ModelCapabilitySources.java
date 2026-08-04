package io.omnnu.finbot.application.configuration.dto;

import java.util.Objects;

public record ModelCapabilitySources(
        CapabilitySource availability,
        CapabilitySource supportedProtocols,
        CapabilitySource maximumReasoningEffort,
        CapabilitySource tokenLimitParameterStyles,
        CapabilitySource streaming,
        CapabilitySource tools,
        CapabilitySource multimodal,
        CapabilitySource maximumContextTokens,
        CapabilitySource maximumInputTokens,
        CapabilitySource maximumOutputTokens) {
    public static final ModelCapabilitySources UNKNOWN = all(CapabilitySource.UNKNOWN);

    public ModelCapabilitySources {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(supportedProtocols, "supportedProtocols");
        Objects.requireNonNull(maximumReasoningEffort, "maximumReasoningEffort");
        Objects.requireNonNull(tokenLimitParameterStyles, "tokenLimitParameterStyles");
        Objects.requireNonNull(streaming, "streaming");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(multimodal, "multimodal");
        Objects.requireNonNull(maximumContextTokens, "maximumContextTokens");
        Objects.requireNonNull(maximumInputTokens, "maximumInputTokens");
        Objects.requireNonNull(maximumOutputTokens, "maximumOutputTokens");
    }

    public static ModelCapabilitySources all(CapabilitySource source) {
        Objects.requireNonNull(source, "source");
        return new ModelCapabilitySources(
                source,
                source,
                source,
                source,
                source,
                source,
                source,
                source,
                source,
                source);
    }
}
