package io.omnnu.finbot.application.operations.port.out;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;

import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.operations.WorkerId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public interface BackgroundTaskStore {
    BackgroundTask enqueue(BackgroundTask task);

    Optional<BackgroundTask> find(BackgroundTaskId taskId);

    List<BackgroundTask> list(BackgroundTaskStatus status, int limit);

    Optional<BackgroundTask> claimNext(
            WorkerId workerId,
            Instant now,
            Duration leaseDuration,
            Set<BackgroundTaskType> allowedTaskTypes);

    long count(BackgroundTaskStatus status);

    boolean heartbeat(BackgroundTaskId taskId, WorkerId workerId, Instant now, Duration leaseDuration);

    boolean complete(BackgroundTaskId taskId, WorkerId workerId, Instant completedAt);

    boolean fail(
            BackgroundTaskId taskId,
            WorkerId workerId,
            String errorCode,
            String errorMessage,
            Instant failedAt,
            Duration retryDelay);

    int recoverExpiredLeases(Instant now);

    int materializeDueSchedules(Instant now, int limit, Supplier<BackgroundTaskId> taskIdSupplier);

    void registerWorker(WorkerId workerId, String instanceName, Instant startedAt);

    void heartbeatWorker(WorkerId workerId, Instant heartbeatAt);

    void stopWorker(WorkerId workerId, Instant stoppedAt);
}
