package io.omnnu.finbot.api.workflow;

import io.omnnu.finbot.application.workflow.WorkflowEventStream;
import io.omnnu.finbot.application.workflow.WorkflowRunQuery;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/workflows")
public final class WorkflowEventController {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final long CLIENT_RECONNECT_MILLIS = 3_000;

    private final WorkflowEventStream workflowEventStream;
    private final WorkflowRunQuery workflowRunQuery;
    private final ScheduledExecutorService heartbeatScheduler;

    public WorkflowEventController(
            WorkflowEventStream workflowEventStream,
            WorkflowRunQuery workflowRunQuery,
            @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.workflowEventStream = Objects.requireNonNull(workflowEventStream, "workflowEventStream");
        this.workflowRunQuery = Objects.requireNonNull(workflowRunQuery, "workflowRunQuery");
        this.heartbeatScheduler = Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    }

    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        var typedRunId = new WorkflowRunId(runId);
        if (workflowRunQuery.find(typedRunId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(0L);
        var subscriber = new SseWorkflowSubscriber(emitter, heartbeatScheduler);
        subscriber.bindEmitterLifecycle();
        workflowEventStream.stream(typedRunId, parseLastSequence(lastEventId)).subscribe(subscriber);
        return emitter;
    }

    private static long parseLastSequence(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            var sequence = Long.parseLong(lastEventId.strip());
            if (sequence < 0) {
                throw new NumberFormatException("negative sequence");
            }
            return sequence;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative event sequence", exception);
        }
    }

    private static final class SseWorkflowSubscriber implements Flow.Subscriber<WorkflowEvent> {
        private final SseEmitter emitter;
        private final ScheduledExecutorService heartbeatScheduler;
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private SseWorkflowSubscriber(
                SseEmitter emitter,
                ScheduledExecutorService heartbeatScheduler) {
            this.emitter = emitter;
            this.heartbeatScheduler = heartbeatScheduler;
        }

        private void bindEmitterLifecycle() {
            emitter.onCompletion(this::cancelResources);
            emitter.onTimeout(() -> {
                cancelResources();
                emitter.complete();
            });
            emitter.onError(ignored -> cancelResources());
        }

        @Override
        public void onSubscribe(Flow.Subscription upstream) {
            if (!subscription.compareAndSet(null, upstream)) {
                upstream.cancel();
                return;
            }
            heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(
                    this::sendHeartbeat,
                    HEARTBEAT_INTERVAL.toSeconds(),
                    HEARTBEAT_INTERVAL.toSeconds(),
                    TimeUnit.SECONDS));
            upstream.request(1);
        }

        @Override
        public void onNext(WorkflowEvent event) {
            if (finished.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.eventType())
                        .reconnectTime(CLIENT_RECONNECT_MILLIS)
                        .data(event));
                var upstream = subscription.get();
                if (upstream != null) {
                    upstream.request(1);
                }
            } catch (IOException | IllegalStateException exception) {
                fail(exception);
            }
        }

        @Override
        public void onError(Throwable failure) {
            fail(failure);
        }

        @Override
        public void onComplete() {
            if (finished.compareAndSet(false, true)) {
                cancelResources();
                emitter.complete();
            }
        }

        private void sendHeartbeat() {
            if (finished.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException exception) {
                fail(exception);
            }
        }

        private void fail(Throwable failure) {
            if (finished.compareAndSet(false, true)) {
                cancelResources();
                emitter.completeWithError(failure);
            }
        }

        private void cancelResources() {
            var upstream = subscription.getAndSet(null);
            if (upstream != null) {
                upstream.cancel();
            }
            var heartbeatTask = heartbeat.getAndSet(null);
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
            }
        }
    }
}
