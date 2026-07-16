package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import java.math.BigDecimal;

public record CreateModelCommand(
        String providerProfileId,
        String modelName,
        ReasoningEffort defaultReasoningEffort,
        ReasoningEffort maximumReasoningEffort,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion,
        boolean enabled) {
}
