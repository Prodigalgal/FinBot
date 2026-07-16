package io.omnnu.finbot.operations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.configuration.WorkerProperties;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.operations.WorkerId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finbot.migration-only", havingValue = "false", matchIfMissing = true)
public final class BackgroundWorkerRuntime implements InitializingBean, DisposableBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundWorkerRuntime.class);

    private final BackgroundTaskCoordinator coordinator;
    private final WorkerProperties properties;
    private final Executor executor;
    private final Map<BackgroundTaskType, BackgroundTaskHandler> handlers;
    private final Map<BackgroundTaskId, RunningTask> runningTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean claiming = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final Counter capacityDeferredCounter;
    private final Counter rejectedExecutionCounter;
    private final Counter lostLeaseCounter;
    private final Counter recoveredLeaseCounter;
    private final WorkerId workerId;
    private final String instanceName;

    public BackgroundWorkerRuntime(
            BackgroundTaskCoordinator coordinator,
            WorkerProperties properties,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor,
            List<BackgroundTaskHandler> taskHandlers,
            SortableIdGenerator idGenerator,
            MeterRegistry meterRegistry) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.handlers = handlers(taskHandlers);
        this.workerId = new WorkerId(idGenerator.next("worker_"));
        this.instanceName = Objects.requireNonNullElse(System.getenv("HOSTNAME"), "finbot-local");
        var registry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.capacityDeferredCounter = Counter.builder("finbot.worker.claim.deferred")
                .description("Task claim polls deferred because worker capacity was exhausted")
                .register(registry);
        this.rejectedExecutionCounter = Counter.builder("finbot.worker.execution.rejected")
                .description("Claimed tasks rejected by the worker executor")
                .register(registry);
        this.lostLeaseCounter = Counter.builder("finbot.worker.lease.lost")
                .description("Running tasks cancelled after lease renewal failed")
                .register(registry);
        this.recoveredLeaseCounter = Counter.builder("finbot.worker.lease.recovered")
                .description("Expired task leases recovered for retry or terminal failure")
                .register(registry);
        Gauge.builder("finbot.worker.tasks.running", runningTasks, Map::size)
                .description("Tasks currently executing in this worker")
                .register(registry);
        Gauge.builder("finbot.worker.tasks.pending", pendingTasks, AtomicInteger::get)
                .description("Persistent pending task queue depth")
                .register(registry);
        Gauge.builder("finbot.worker.capacity.available", this, BackgroundWorkerRuntime::availableCapacity)
                .description("Available global worker execution capacity")
                .register(registry);
        for (var type : BackgroundTaskType.values()) {
            Gauge.builder("finbot.worker.tasks.running.by.type", this, runtime -> runtime.runningCount(type))
                    .tag("task.type", type.name())
                    .description("Running tasks by task type")
                    .register(registry);
        }
    }

    @Override
    public void afterPropertiesSet() {
        coordinator.registerWorker(workerId, instanceName);
        var recovered = coordinator.recoverExpiredLeases();
        recoveredLeaseCounter.increment(recovered);
        LOGGER.info("FinBot worker {} started; recovered {} expired leases", workerId.value(), recovered);
    }

    @Scheduled(fixedDelayString = "${finbot.worker.poll-delay:PT2S}")
    public void claimAvailableTask() {
        if (!active.get() || !claiming.compareAndSet(false, true)) {
            return;
        }
        try {
            pendingTasks.set(Math.toIntExact(Math.min(Integer.MAX_VALUE, coordinator.count(BackgroundTaskStatus.PENDING))));
            var allowedTypes = allowedTaskTypes();
            if (allowedTypes.isEmpty()) {
                capacityDeferredCounter.increment();
                return;
            }
            coordinator.claim(workerId, properties.leaseDuration(), allowedTypes).ifPresent(this::submit);
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
        runningTasks.values().forEach(running -> {
            if (running.leaseActive()
                    && !coordinator.heartbeat(running.task(), workerId, properties.leaseDuration())) {
                lostLeaseCounter.increment();
                running.cancelForLostLease();
                LOGGER.error("Lost lease for task {}; local execution cancelled", running.task().taskId().value());
            }
        });
    }

    @Scheduled(fixedDelayString = "${finbot.worker.lease-recovery-interval:PT10S}")
    public void recoverExpiredLeases() {
        if (!active.get()) {
            return;
        }
        var recovered = coordinator.recoverExpiredLeases();
        if (recovered > 0) {
            recoveredLeaseCounter.increment(recovered);
            LOGGER.warn("Recovered {} expired task leases during worker runtime", recovered);
        }
    }

    private void submit(BackgroundTask task) {
        var running = new RunningTask(task);
        var duplicate = runningTasks.putIfAbsent(task.taskId(), running);
        if (duplicate != null) {
            failClaimedTask(task, "DUPLICATE_LOCAL_CLAIM", "Task is already running in this worker");
            return;
        }
        var future = new FutureTask<Void>(() -> {
            execute(running);
            return null;
        });
        running.attachExecution(future);
        try {
            executor.execute(future);
        } catch (RejectedExecutionException exception) {
            rejectedExecutionCounter.increment();
            runningTasks.remove(task.taskId(), running);
            failClaimedTask(task, "WORKER_EXECUTOR_REJECTED", "Worker executor rejected the claimed task");
        }
    }

    private void execute(RunningTask running) {
        var task = running.task();
        try {
            var handler = handlers.get(task.taskType());
            if (handler == null) {
                throw new MissingTaskHandlerException(task.taskType());
            }
            var handlerFuture = handler.handle(task).toCompletableFuture();
            running.attachHandler(handlerFuture);
            handlerFuture.get();
            if (running.canCommit(active.get())) {
                coordinator.complete(task, workerId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (running.canCommit(active.get())) {
                failClaimedTask(task, "TASK_INTERRUPTED", "Task execution was interrupted");
            }
        } catch (CancellationException exception) {
            if (running.canCommit(active.get())) {
                failClaimedTask(task, "TASK_CANCELLED", "Task execution was cancelled");
            }
        } catch (ExecutionException | RuntimeException exception) {
            if (running.canCommit(active.get())) {
                var cause = rootCause(exception);
                failClaimedTask(task, cause.getClass().getSimpleName(), safeMessage(cause));
                LOGGER.error("Task {} ({}) failed: {}", task.taskId().value(), task.taskType(), safeMessage(cause));
            }
        } finally {
            runningTasks.remove(task.taskId(), running);
        }
    }

    private void failClaimedTask(BackgroundTask task, String errorCode, String errorMessage) {
        try {
            coordinator.fail(task, workerId, errorCode, errorMessage, properties.retryDelay());
        } catch (IllegalStateException lostLease) {
            LOGGER.warn("Could not record task {} failure because its lease is no longer owned", task.taskId().value());
        }
    }

    private Set<BackgroundTaskType> allowedTaskTypes() {
        if (runningTasks.size() >= properties.maximumConcurrentTasks()) {
            return Set.of();
        }
        var allowed = EnumSet.noneOf(BackgroundTaskType.class);
        for (var type : BackgroundTaskType.values()) {
            if (runningCount(type) < properties.maximumConcurrentTasks(type)) {
                allowed.add(type);
            }
        }
        return allowed;
    }

    private int runningCount(BackgroundTaskType type) {
        return (int) runningTasks.values().stream()
                .filter(task -> task.task().taskType() == type)
                .count();
    }

    private int availableCapacity() {
        return Math.max(0, properties.maximumConcurrentTasks() - runningTasks.size());
    }

    @Override
    public void destroy() {
        active.set(false);
        runningTasks.values().forEach(RunningTask::cancelForShutdown);
        coordinator.stopWorker(workerId);
        LOGGER.info("FinBot worker {} stopped with {} tasks cancelled", workerId.value(), runningTasks.size());
    }

    private static Map<BackgroundTaskType, BackgroundTaskHandler> handlers(List<BackgroundTaskHandler> taskHandlers) {
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

    private static final class RunningTask {
        private final BackgroundTask task;
        private final AtomicBoolean leaseActive = new AtomicBoolean(true);
        private volatile FutureTask<Void> execution;
        private volatile CompletableFuture<Void> handler;

        private RunningTask(BackgroundTask task) {
            this.task = task;
        }

        private BackgroundTask task() {
            return task;
        }

        private boolean leaseActive() {
            return leaseActive.get();
        }

        private boolean canCommit(boolean runtimeActive) {
            return runtimeActive && leaseActive.get() && !Thread.currentThread().isInterrupted();
        }

        private void attachExecution(FutureTask<Void> future) {
            execution = future;
        }

        private void attachHandler(CompletableFuture<Void> future) {
            handler = future;
            if (!leaseActive.get()) {
                future.cancel(true);
            }
        }

        private void cancelForLostLease() {
            leaseActive.set(false);
            cancel();
        }

        private void cancelForShutdown() {
            leaseActive.set(false);
            cancel();
        }

        private void cancel() {
            var handlerFuture = handler;
            if (handlerFuture != null) {
                handlerFuture.cancel(true);
            }
            var executionFuture = execution;
            if (executionFuture != null) {
                executionFuture.cancel(true);
            }
        }
    }

    private static final class MissingTaskHandlerException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MissingTaskHandlerException(BackgroundTaskType type) {
            super("No handler registered for " + type);
        }
    }
}
