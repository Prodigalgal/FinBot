package io.omnnu.finbot.operations;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.configuration.WorkerProperties;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.operations.WorkerId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "finbot.migration-only", havingValue = "false", matchIfMissing = true)
public final class BackgroundWorkerRuntime implements InitializingBean, DisposableBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundWorkerRuntime.class);

    private final BackgroundTaskCoordinator coordinator;
    private final WorkerProperties properties;
    private final Executor executor;
    private final Map<BackgroundTaskType, BackgroundTaskHandler> handlers;
    private final Map<BackgroundTaskId, BackgroundTask> runningTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean claiming = new AtomicBoolean();
    private final WorkerId workerId;
    private final String instanceName;

    public BackgroundWorkerRuntime(
            BackgroundTaskCoordinator coordinator,
            WorkerProperties properties,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor,
            List<BackgroundTaskHandler> taskHandlers,
            SortableIdGenerator idGenerator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.handlers = handlers(taskHandlers);
        this.workerId = new WorkerId(idGenerator.next("worker_"));
        this.instanceName = Objects.requireNonNullElse(System.getenv("HOSTNAME"), "finbot-local");
    }

    @Override
    public void afterPropertiesSet() {
        coordinator.registerWorker(workerId, instanceName);
        var recovered = coordinator.recoverExpiredLeases();
        LOGGER.info("FinBot worker {} started; recovered {} expired leases", workerId.value(), recovered);
    }

    @Scheduled(fixedDelayString = "${finbot.worker.poll-delay:PT2S}")
    public void claimAvailableTask() {
        if (!claiming.compareAndSet(false, true)) {
            return;
        }
        try {
            coordinator.claim(workerId, properties.leaseDuration()).ifPresent(task -> {
                runningTasks.put(task.taskId(), task);
                executor.execute(() -> execute(task));
            });
        } finally {
            claiming.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${finbot.worker.scheduler-poll-delay:PT5S}")
    public void materializeSchedules() {
        var count = coordinator.materializeDueSchedules(properties.maximumDueSchedules());
        if (count > 0) {
            LOGGER.info("Materialized {} due FinBot schedules", count);
        }
    }

    @Scheduled(fixedDelayString = "${finbot.worker.heartbeat-interval:PT5S}")
    public void heartbeat() {
        coordinator.heartbeatWorker(workerId);
        runningTasks.values().forEach(task -> {
            if (!coordinator.heartbeat(task, workerId, properties.leaseDuration())) {
                LOGGER.error("Lost lease for task {}", task.taskId().value());
            }
        });
    }

    private void execute(BackgroundTask task) {
        try {
            var handler = handlers.get(task.taskType());
            if (handler == null) {
                throw new MissingTaskHandlerException(task.taskType());
            }
            handler.handle(task).toCompletableFuture().join();
            coordinator.complete(task, workerId);
        } catch (RuntimeException exception) {
            var cause = rootCause(exception);
            coordinator.fail(
                    task,
                    workerId,
                    cause.getClass().getSimpleName(),
                    safeMessage(cause),
                    properties.retryDelay());
            LOGGER.error("Task {} ({}) failed: {}", task.taskId().value(), task.taskType(), safeMessage(cause));
        } finally {
            runningTasks.remove(task.taskId());
        }
    }

    @Override
    public void destroy() {
        coordinator.stopWorker(workerId);
        LOGGER.info("FinBot worker {} stopped with {} tasks still running", workerId.value(), runningTasks.size());
    }

    private static Map<BackgroundTaskType, BackgroundTaskHandler> handlers(
            List<BackgroundTaskHandler> taskHandlers) {
        var handlers = new EnumMap<BackgroundTaskType, BackgroundTaskHandler>(BackgroundTaskType.class);
        for (var handler : taskHandlers) {
            var duplicate = handlers.put(handler.taskType(), handler);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate task handler for " + handler.taskType());
            }
        }
        return Map.copyOf(handlers);
    }

    private static Throwable rootCause(Throwable error) {
        var current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        var message = Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
        var redacted = message
                .replaceAll("(?i)(api[_-]?key|secret|token|password)\\s*[=:]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .strip();
        return redacted.substring(0, Math.min(redacted.length(), 2000));
    }

    private static final class MissingTaskHandlerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MissingTaskHandlerException(BackgroundTaskType type) {
            super("No handler registered for " + type);
        }
    }
}
