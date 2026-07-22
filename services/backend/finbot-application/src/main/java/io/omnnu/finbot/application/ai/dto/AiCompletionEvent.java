package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public sealed interface AiCompletionEvent permits
        AiStreamStarted,
        AiTextDelta,
        AiUsageReported,
        AiCompletionFinished,
        AiCompletionFailed {
    AiInvocationId invocationId();

    Instant occurredAt();
}
