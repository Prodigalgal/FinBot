package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.operations.WorkerId;
import java.time.Instant;
import java.util.Objects;

public record BackgroundTask(
        BackgroundTaskId taskId,
        BackgroundTaskType taskType,
        BackgroundTaskStatus status,
        int priority,
        String idempotencyKey,
        BackgroundTaskPayload payload,
        int attemptCount,
        int maximumAttempts,
        Instant availableAt,
        Instant claimedAt,
        Instant leaseExpiresAt,
        WorkerId claimOwner,
        Instant heartbeatAt,
        Instant completedAt,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
    public BackgroundTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(status, "status");
        if (priority < 0 || priority > 100) {
            throw new IllegalArgumentException("priority must be between 0 and 100");
        }
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
        Objects.requireNonNull(payload, "payload");
        if (attemptCount < 0 || maximumAttempts < 1 || attemptCount > maximumAttempts) {
            throw new IllegalArgumentException("Invalid task attempt counters");
        }
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requirePayloadType(taskType, payload);
    }

    private static void requirePayloadType(BackgroundTaskType type, BackgroundTaskPayload payload) {
        var matches = switch (type) {
            case SCHEDULED_RESEARCH -> payload instanceof ScheduledResearchTaskPayload;
            case INSTANT_RESEARCH -> payload instanceof InstantResearchTaskPayload;
            case ACCOUNT_SYNC, ORDER_RECONCILIATION -> payload instanceof AccountTaskPayload;
            case MARKET_DATA_SYNC -> payload instanceof MarketDataTaskPayload;
            case INGESTION -> payload instanceof IngestionTaskPayload;
            case CATALOG_SYNC -> payload instanceof CatalogSyncTaskPayload;
            case FORECAST_EVALUATION -> payload instanceof ForecastEvaluationTaskPayload;
        };
        if (!matches) {
            throw new IllegalArgumentException("Payload type does not match " + type);
        }
    }
}
