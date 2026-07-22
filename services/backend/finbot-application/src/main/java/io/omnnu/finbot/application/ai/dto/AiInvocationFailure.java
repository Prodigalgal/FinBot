package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.time.Instant;

public record AiInvocationFailure(
        AiInvocationId invocationId,
        String errorCode,
        String safeMessage,
        Instant failedAt) {
}
