package io.omnnu.finbot.infrastructure.workflow.persistence;

import io.omnnu.finbot.infrastructure.workflow.persistence.PostgresPollingWorkflowEventStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.workflow.port.out.WorkflowEventReader;
import io.omnnu.finbot.domain.workflow.WorkflowAccepted;
import io.omnnu.finbot.domain.workflow.WorkflowCompleted;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PostgresPollingWorkflowEventStreamTest {
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_01j0000000001");
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    @Test
    void resumesAfterSequenceAndCompletesOnTerminalEvent() {
        var accepted = new WorkflowAccepted(
                new WorkflowEventId("event_01j0000000001"),
                RUN_ID,
                1,
                WorkflowType.INSTANT_RESEARCH,
                NOW);
        var completed = new WorkflowCompleted(
                new WorkflowEventId("event_01j0000000002"),
                RUN_ID,
                2,
                "artifact://research/run_01j0000000001",
                NOW.plusSeconds(1));
        var persisted = List.<WorkflowEvent>of(accepted, completed);
        WorkflowEventReader reader = (runId, afterSequence, limit) -> persisted.stream()
                .filter(event -> event.sequence() > afterSequence)
                .limit(limit)
                .toList();
        var stream = new PostgresPollingWorkflowEventStream(
                reader,
                Runnable::run,
                Duration.ofMillis(1));
        var received = new ArrayList<WorkflowEvent>();
        var completedSignal = new AtomicBoolean();

        stream.stream(RUN_ID, 1).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(WorkflowEvent event) {
                received.add(event);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
                completedSignal.set(true);
            }
        });

        assertEquals(List.of(completed), received);
        assertTrue(completedSignal.get());
    }
}
