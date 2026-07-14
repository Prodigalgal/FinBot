package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;
import java.util.Objects;

public record AiTextDelta(
        AiInvocationId invocationId,
        long sequence,
        String text,
        Instant occurredAt) implements AiCompletionEvent {
    public AiTextDelta {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        text = Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
    }
}
