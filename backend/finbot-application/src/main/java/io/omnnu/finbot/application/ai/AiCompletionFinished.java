package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;
import java.util.Objects;

public record AiCompletionFinished(
        AiInvocationId invocationId,
        String finishReason,
        Instant occurredAt) implements AiCompletionEvent {
    public AiCompletionFinished {
        finishReason = Objects.requireNonNullElse(finishReason, "completed").strip();
    }
}
