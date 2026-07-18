package io.omnnu.finbot.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.BackgroundTaskHandler;
import io.omnnu.finbot.configuration.WorkerProperties;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BackgroundWorkerRuntimeTest {
    @Test
    void defersClaimWhenGlobalCapacityIsExhausted() throws Exception {
        var coordinator = mock(BackgroundTaskCoordinator.class);
        var task = task("task_capacity", BackgroundTaskType.INSTANT_RESEARCH);
        var handlerStarted = new CountDownLatch(1);
        var handlerResult = new CompletableFuture<Void>();
        var handler = handler(BackgroundTaskType.INSTANT_RESEARCH, handlerStarted, handlerResult);
        when(coordinator.count(BackgroundTaskStatus.PENDING)).thenReturn(2L);
        when(coordinator.claim(any(), any(), any())).thenReturn(Optional.of(task));
        var registry = new SimpleMeterRegistry();
        var executor = Executors.newSingleThreadExecutor();
        var runtime = new BackgroundWorkerRuntime(
                coordinator,
                properties(1, 1),
                executor,
                java.util.List.of(handler),
                prefix -> prefix + "runtime_test",
                registry);
        try {
            runtime.claimAvailableTask();
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));

            runtime.claimAvailableTask();

            verify(coordinator, times(1)).claim(any(), any(), any());
            assertTrue(registry.get("finbot.worker.claim.deferred").counter().count() >= 1);
        } finally {
            runtime.destroy();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelsLocalExecutionAndDoesNotCommitAfterLeaseLoss() throws Exception {
        var coordinator = mock(BackgroundTaskCoordinator.class);
        var task = task("task_lost_lease", BackgroundTaskType.INSTANT_RESEARCH);
        var handlerStarted = new CountDownLatch(1);
        var handlerCancelled = new CountDownLatch(1);
        var handlerResult = new CompletableFuture<Void>();
        handlerResult.whenComplete((ignored, failure) -> handlerCancelled.countDown());
        var handler = handler(BackgroundTaskType.INSTANT_RESEARCH, handlerStarted, handlerResult);
        when(coordinator.count(BackgroundTaskStatus.PENDING)).thenReturn(1L);
        when(coordinator.claim(any(), any(), any())).thenReturn(Optional.of(task));
        when(coordinator.heartbeat(eq(task), any(), any())).thenReturn(false);
        var registry = new SimpleMeterRegistry();
        var executor = Executors.newSingleThreadExecutor();
        var runtime = new BackgroundWorkerRuntime(
                coordinator,
                properties(1, 1),
                executor,
                java.util.List.of(handler),
                prefix -> prefix + "runtime_test",
                registry);
        try {
            runtime.claimAvailableTask();
            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));

            runtime.heartbeat();

            assertTrue(handlerCancelled.await(1, TimeUnit.SECONDS));
            assertTrue(handlerResult.isCancelled());
            verify(coordinator, after(250).never()).complete(any(), any());
            verify(coordinator, never()).fail(any(), any(), any(), any(), any());
            assertTrue(registry.get("finbot.worker.lease.lost").counter().count() >= 1);
        } finally {
            runtime.destroy();
            executor.shutdownNow();
        }
    }

    @Test
    void periodicallyRecoversExpiredLeases() {
        var coordinator = mock(BackgroundTaskCoordinator.class);
        when(coordinator.recoverExpiredLeases()).thenReturn(2);
        var registry = new SimpleMeterRegistry();
        var executor = Executors.newSingleThreadExecutor();
        var runtime = new BackgroundWorkerRuntime(
                coordinator,
                properties(1, 1),
                executor,
                java.util.List.of(),
                prefix -> prefix + "runtime_test",
                registry);
        try {
            runtime.recoverExpiredLeases();

            verify(coordinator, times(1)).recoverExpiredLeases();
            assertEquals(2.0, registry.get("finbot.worker.lease.recovered").counter().count());
        } finally {
            runtime.destroy();
            executor.shutdownNow();
        }
    }

    private static BackgroundTask task(String id, BackgroundTaskType type) {
        var task = mock(BackgroundTask.class);
        when(task.taskId()).thenReturn(new BackgroundTaskId(id));
        when(task.taskType()).thenReturn(type);
        return task;
    }

    private static BackgroundTaskHandler handler(
            BackgroundTaskType type,
            CountDownLatch started,
            CompletableFuture<Void> result) {
        return new BackgroundTaskHandler() {
            @Override
            public BackgroundTaskType taskType() {
                return type;
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> handle(BackgroundTask task) {
                started.countDown();
                return result;
            }
        };
    }

    private static WorkerProperties properties(int globalLimit, int typeLimit) {
        var limits = Arrays.stream(BackgroundTaskType.values())
                .collect(Collectors.toMap(type -> type, ignored -> typeLimit));
        return new WorkerProperties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                10,
                globalLimit,
                limits);
    }
}
