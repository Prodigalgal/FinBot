package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public record AiStreamStarted(AiInvocationId invocationId, Instant occurredAt) implements AiCompletionEvent {
}
