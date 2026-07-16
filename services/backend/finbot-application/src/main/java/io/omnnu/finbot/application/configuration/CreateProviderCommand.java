package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.math.BigDecimal;

public record CreateProviderCommand(
        String displayName,
        AiProtocol protocol,
        ReasoningParameterStyle reasoningParameterStyle,
        String baseUrl,
        boolean enabled,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        String initialModelName,
        ReasoningEffort defaultReasoningEffort,
        ReasoningEffort maximumReasoningEffort,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion) {
}
