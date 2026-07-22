package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public record AiInvocationCompletion(
        AiInvocationId invocationId,
        long inputTokens,
        long outputTokens,
        String finishReason,
        Instant completedAt) {
}
