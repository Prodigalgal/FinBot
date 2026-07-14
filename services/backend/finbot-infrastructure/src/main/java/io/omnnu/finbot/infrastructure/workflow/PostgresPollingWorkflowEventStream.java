package io.omnnu.finbot.infrastructure.workflow;

import io.omnnu.finbot.application.workflow.WorkflowEventReader;
import io.omnnu.finbot.application.workflow.WorkflowEventStream;
import io.omnnu.finbot.domain.workflow.WorkflowCompleted;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowFailed;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PostgresPollingWorkflowEventStream implements WorkflowEventStream {
    private static final int REPLAY_BATCH_SIZE = 500;

    private final WorkflowEventReader eventReader;
    private final Executor executor;
    private final Duration pollInterval;

    public PostgresPollingWorkflowEventStream(
            WorkflowEventReader eventReader,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor,
            @Value("${finbot.workflow.event-poll-interval:PT0.5S}") Duration pollInterval) {
        this.eventReader = Objects.requireNonNull(eventReader, "eventReader");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.pollInterval = requirePollInterval(pollInterval);
    }

    @Override
    public Flow.Publisher<WorkflowEvent> stream(WorkflowRunId runId, long afterSequence) {
        Objects.requireNonNull(runId, "runId");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            subscriber.onSubscribe(new PollingSubscription(subscriber, runId, afterSequence));
        };
    }

    private static Duration requirePollInterval(Duration value) {
        Objects.requireNonNull(value, "pollInterval");
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("pollInterval must be between 1 nanosecond and 30 seconds");
        }
        return value;
    }

    private final class PollingSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super WorkflowEvent> downstream;
        private final WorkflowRunId runId;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final Object monitor = new Object();
        private long cursor;

        private PollingSubscription(
                Flow.Subscriber<? super WorkflowEvent> downstream,
                WorkflowRunId runId,
                long afterSequence) {
            this.downstream = downstream;
            this.runId = runId;
            cursor = afterSequence;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                signalError(new IllegalArgumentException("subscriber demand must be positive"));
                return;
            }
            demand.getAndAccumulate(count, PostgresPollingWorkflowEventStream::saturatedAdd);
            synchronized (monitor) {
                monitor.notifyAll();
            }
            if (started.compareAndSet(false, true)) {
                executor.execute(this::poll);
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        private void poll() {
            try {
                while (!cancelled.get()) {
                    awaitDemand();
                    if (cancelled.get()) {
                        return;
                    }
                    var events = eventReader.loadAfter(runId, cursor, REPLAY_BATCH_SIZE);
                    if (events.isEmpty()) {
                        awaitNextPoll();
                        continue;
                    }
                    for (var event : events) {
                        awaitDemand();
                        if (cancelled.get()) {
                            return;
                        }
                        downstream.onNext(event);
                        cursor = event.sequence();
                        demand.decrementAndGet();
                        if (event instanceof WorkflowCompleted || event instanceof WorkflowFailed) {
                            signalComplete();
                            return;
                        }
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                signalError(new IllegalStateException("Workflow event stream was interrupted", exception));
            } catch (RuntimeException exception) {
                signalError(exception);
            }
        }

        private void awaitDemand() throws InterruptedException {
            synchronized (monitor) {
                while (demand.get() == 0 && !cancelled.get()) {
                    monitor.wait();
                }
            }
        }

        private void awaitNextPoll() throws InterruptedException {
            var millis = pollInterval.toMillis();
            var nanos = pollInterval.minusMillis(millis).getNano();
            synchronized (monitor) {
                if (!cancelled.get()) {
                    monitor.wait(millis, nanos);
                }
            }
        }

        private void signalComplete() {
            if (terminated.compareAndSet(false, true) && !cancelled.getAndSet(true)) {
                downstream.onComplete();
            }
        }

        private void signalError(Throwable failure) {
            if (terminated.compareAndSet(false, true) && !cancelled.getAndSet(true)) {
                downstream.onError(failure);
            }
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    private static long saturatedAdd(long current, long increment) {
        var result = current + increment;
        return result < 0 ? Long.MAX_VALUE : result;
    }
}
