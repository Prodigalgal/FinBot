package io.omnnu.finbot.application.operations;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.WorkerId;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BackgroundTaskCoordinator {
    private final BackgroundTaskStore store;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public BackgroundTaskCoordinator(
            BackgroundTaskStore store,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BackgroundTask enqueue(EnqueueTaskCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        var task = new BackgroundTask(
                nextTaskId(),
                command.taskType(),
                BackgroundTaskStatus.PENDING,
                command.priority(),
                command.idempotencyKey(),
                command.payload(),
                0,
                command.maximumAttempts(),
                command.availableAt() == null ? now : command.availableAt(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now);
        return store.enqueue(task);
    }

    public Optional<BackgroundTask> claim(WorkerId workerId, Duration leaseDuration) {
        return store.claimNext(workerId, clock.instant(), leaseDuration);
    }

    public void complete(BackgroundTask task, WorkerId workerId) {
        if (!store.complete(task.taskId(), workerId, clock.instant())) {
            throw new IllegalStateException("Task lease was lost before completion: " + task.taskId().value());
        }
    }

    public void fail(
            BackgroundTask task,
            WorkerId workerId,
            String errorCode,
            String errorMessage,
            Duration retryDelay) {
        if (!store.fail(
                task.taskId(),
                workerId,
                errorCode,
                errorMessage,
                clock.instant(),
                retryDelay)) {
            throw new IllegalStateException("Task lease was lost before failure handling: " + task.taskId().value());
        }
    }

    public List<BackgroundTask> list(BackgroundTaskStatus status, int limit) {
        return store.list(status, limit);
    }

    public Optional<BackgroundTask> find(BackgroundTaskId taskId) {
        return store.find(taskId);
    }

    public int materializeDueSchedules(int limit) {
        return store.materializeDueSchedules(clock.instant(), limit, this::nextTaskId);
    }

    public int recoverExpiredLeases() {
        return store.recoverExpiredLeases(clock.instant());
    }

    public boolean heartbeat(BackgroundTask task, WorkerId workerId, Duration leaseDuration) {
        return store.heartbeat(task.taskId(), workerId, clock.instant(), leaseDuration);
    }

    public void registerWorker(WorkerId workerId, String instanceName) {
        store.registerWorker(workerId, instanceName, clock.instant());
    }

    public void heartbeatWorker(WorkerId workerId) {
        store.heartbeatWorker(workerId, clock.instant());
    }

    public void stopWorker(WorkerId workerId) {
        store.stopWorker(workerId, clock.instant());
    }

    private BackgroundTaskId nextTaskId() {
        return new BackgroundTaskId(idGenerator.next("task_"));
    }
}
