package io.omnnu.finbot.api.operations;

import io.omnnu.finbot.application.operations.OperationsRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/operations")
public final class OperationsEventController {
    private static final Duration SNAPSHOT_INTERVAL = Duration.ofSeconds(5);
    private static final long RECONNECT_MILLISECONDS = 3_000;

    private final OperationsRepository repository;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Executor executor;

    public OperationsEventController(
            OperationsRepository repository,
            Clock clock,
            @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService scheduler,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        parseLastEventId(lastEventId);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(0L);
        var stream = new SnapshotStream(emitter);
        stream.bindLifecycle();
        stream.start();
        return emitter;
    }

    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            var parsed = Long.parseLong(value.strip());
            if (parsed < 0) {
                throw new NumberFormatException("negative event id");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative timestamp", exception);
        }
    }

    private final class SnapshotStream {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean sending = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> schedule = new AtomicReference<>();

        private SnapshotStream(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void bindLifecycle() {
            emitter.onCompletion(this::close);
            emitter.onTimeout(() -> {
                close();
                emitter.complete();
            });
            emitter.onError(ignored -> close());
        }

        private void start() {
            var task = scheduler.scheduleAtFixedRate(
                    this::dispatch,
                    SNAPSHOT_INTERVAL.toSeconds(),
                    SNAPSHOT_INTERVAL.toSeconds(),
                    TimeUnit.SECONDS);
            schedule.set(task);
            if (closed.get()) {
                cancelSchedule();
                return;
            }
            dispatch();
        }

        private void dispatch() {
            if (closed.get() || !sending.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture.runAsync(this::sendSnapshot, executor)
                    .whenComplete((ignored, failure) -> {
                        sending.set(false);
                        if (failure != null) {
                            fail(failure);
                        }
                    });
        }

        private void sendSnapshot() {
            if (closed.get()) {
                return;
            }
            var generatedAt = clock.instant();
            var snapshot = repository.overview(generatedAt);
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(generatedAt.toEpochMilli()))
                        .name("operations.snapshot")
                        .reconnectTime(RECONNECT_MILLISECONDS)
                        .data(snapshot));
            } catch (IOException | IllegalStateException exception) {
                throw new OperationsStreamException(exception);
            }
        }

        private void fail(Throwable failure) {
            if (closed.compareAndSet(false, true)) {
                cancelSchedule();
                emitter.completeWithError(failure);
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                cancelSchedule();
            }
        }

        private void cancelSchedule() {
            var task = schedule.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static final class OperationsStreamException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private OperationsStreamException(Throwable cause) {
            super("Operations event stream failed", cause);
        }
    }
}
