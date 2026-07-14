package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.time.Instant;

public record EnqueueTaskCommand(
        BackgroundTaskType taskType,
        String idempotencyKey,
        BackgroundTaskPayload payload,
        int priority,
        int maximumAttempts,
        Instant availableAt) {
}
