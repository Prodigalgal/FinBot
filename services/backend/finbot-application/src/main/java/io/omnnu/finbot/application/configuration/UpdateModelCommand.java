package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import java.math.BigDecimal;

public record UpdateModelCommand(
        String modelProfileId,
        ReasoningEffort defaultReasoningEffort,
        ReasoningEffort maximumReasoningEffort,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion,
        boolean enabled,
        long expectedVersion) {
}
