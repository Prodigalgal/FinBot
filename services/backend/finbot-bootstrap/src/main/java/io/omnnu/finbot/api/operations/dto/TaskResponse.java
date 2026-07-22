package io.omnnu.finbot.api.operations.dto;

import io.omnnu.finbot.application.operations.dto.AccountTaskPayload;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.dto.CatalogSyncTaskPayload;
import io.omnnu.finbot.application.operations.dto.ForecastEvaluationTaskPayload;
import io.omnnu.finbot.application.operations.dto.IngestionTaskPayload;
import io.omnnu.finbot.application.operations.dto.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.dto.MarketDataTaskPayload;
import io.omnnu.finbot.application.operations.dto.ScheduledResearchTaskPayload;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.time.Instant;

public record TaskResponse(
        String taskId,
        BackgroundTaskType taskType,
        BackgroundTaskStatus status,
        int priority,
        String payloadSummary,
        int attemptCount,
        int maximumAttempts,
        Instant availableAt,
        Instant claimedAt,
        Instant leaseExpiresAt,
        String claimOwner,
        Instant heartbeatAt,
        Instant completedAt,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
    public static TaskResponse from(BackgroundTask task) {
        return new TaskResponse(
                task.taskId().value(),
                task.taskType(),
                task.status(),
                task.priority(),
                summary(task),
                task.attemptCount(),
                task.maximumAttempts(),
                task.availableAt(),
                task.claimedAt(),
                task.leaseExpiresAt(),
                task.claimOwner() == null ? null : task.claimOwner().value(),
                task.heartbeatAt(),
                task.completedAt(),
                task.errorCode(),
                task.errorMessage(),
                task.createdAt(),
                task.updatedAt());
    }

    private static String summary(BackgroundTask task) {
        return switch (task.payload()) {
            case ScheduledResearchTaskPayload payload -> payload.requestSummary();
            case InstantResearchTaskPayload payload -> payload.question();
            case AccountTaskPayload payload -> payload.accountId().value();
            case MarketDataTaskPayload payload -> payload.instrumentId().value();
            case IngestionTaskPayload payload -> payload.sourceId().value() + ": " + payload.query();
            case CatalogSyncTaskPayload payload -> payload.scope().exchange().name()
                    + " / " + payload.scope().marketType().name();
            case ForecastEvaluationTaskPayload payload -> "最多评估 " + payload.limit() + " 条到期预测";
        };
    }
}
