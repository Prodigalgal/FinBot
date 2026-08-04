package io.omnnu.finbot.application.configuration.dto;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.math.BigDecimal;

public record UpdateModelCommand(
        String modelProfileId,
        ReasoningEffort defaultReasoningEffort,
        ReasoningEffort maximumReasoningEffort,
        TokenLimitParameterStyle tokenLimitParameterStyle,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion,
        boolean enabled,
        long expectedVersion) {
}
