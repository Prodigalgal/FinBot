package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;
import java.util.Objects;

public record AiCompletionFailed(
        AiInvocationId invocationId,
        String errorCode,
        String safeMessage,
        boolean retryable,
        Instant occurredAt) implements AiCompletionEvent {
    public AiCompletionFailed {
        errorCode = Objects.requireNonNull(errorCode, "errorCode").strip();
        safeMessage = Objects.requireNonNull(safeMessage, "safeMessage").strip();
    }
}
