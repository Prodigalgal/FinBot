package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record OperationsOverview(
        Map<BackgroundTaskStatus, Long> taskCounts,
        List<Schedule> schedules,
        List<Worker> workers,
        List<LegacyImport> legacyImports,
        Instant generatedAt) {
    public OperationsOverview {
        taskCounts = Map.copyOf(taskCounts);
        schedules = List.copyOf(schedules);
        workers = List.copyOf(workers);
        legacyImports = List.copyOf(legacyImports);
    }

    public record Schedule(
            String scheduleId,
            String displayName,
            BackgroundTaskType taskType,
            boolean enabled,
            int intervalSeconds,
            int priority,
            int maximumAttempts,
            Instant nextRunAt,
            Instant lastScheduledAt,
            long version,
            Instant updatedAt) {
    }

    public record Worker(
            String workerId,
            String instanceName,
            String status,
            Instant startedAt,
            Instant heartbeatAt,
            Instant stoppedAt) {
    }

    public record LegacyImport(
            String importId,
            String sourceName,
            String sourceSha256,
            String status,
            int sourceTableCount,
            long sourceRowCount,
            long archivedRowCount,
            long transformedRowCount,
            Instant startedAt,
            Instant completedAt,
            String errorSummary) {
    }
}
