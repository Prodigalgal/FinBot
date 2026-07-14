package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public record AiUsageReported(
        AiInvocationId invocationId,
        long inputTokens,
        long outputTokens,
        Instant occurredAt) implements AiCompletionEvent {
    public AiUsageReported {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token usage must not be negative");
        }
    }
}
