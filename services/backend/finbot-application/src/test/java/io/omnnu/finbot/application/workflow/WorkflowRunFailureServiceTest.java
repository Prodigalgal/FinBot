package io.omnnu.finbot.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowFailed;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkflowRunFailureServiceTest {
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_failure_test001");
    private static final Instant FAILED_AT = Instant.parse("2026-07-14T07:31:00Z");

    @Test
    void publishesOneTerminalEventAfterTheStateTransitionSucceeds() {
        var storeCall = new AtomicReference<StoreCall>();
        WorkflowRunFailureStore store = (runId, errorCode, safeMessage, failedAt) -> {
            storeCall.set(new StoreCall(runId, errorCode, safeMessage, failedAt));
            return true;
        };
        var published = new AtomicReference<WorkflowEvent>();
        WorkflowEventPublisher publisher = (runId, factory) -> {
            var event = factory.create(
                    new WorkflowEventId("event_failure_test001"),
                    2,
                    FAILED_AT);
            published.set(event);
            return event;
        };
        var service = new WorkflowRunFailureService(store, publisher);

        assertTrue(service.fail(
                RUN_ID,
                "RESEARCH_MARKET_DATA_FAILED",
                "Market data preparation failed before workflow execution",
                true,
                FAILED_AT));

        assertEquals(RUN_ID, storeCall.get().runId());
        var event = assertInstanceOf(WorkflowFailed.class, published.get());
        assertEquals("RESEARCH_MARKET_DATA_FAILED", event.errorCode());
        assertEquals("Market data preparation failed before workflow execution", event.safeMessage());
        assertTrue(event.retryable());
        assertEquals(FAILED_AT, event.occurredAt());
    }

    @Test
    void doesNotPublishWhenTheRunIsAlreadyTerminal() {
        WorkflowRunFailureStore store = (runId, errorCode, safeMessage, failedAt) -> false;
        var publisherCalled = new AtomicBoolean();
        WorkflowEventPublisher publisher = (runId, factory) -> {
            publisherCalled.set(true);
            throw new AssertionError("A terminal workflow must not receive another failure event");
        };
        var service = new WorkflowRunFailureService(store, publisher);

        assertFalse(service.fail(
                RUN_ID,
                "RESEARCH_MARKET_DATA_FAILED",
                "Market data preparation failed before workflow execution",
                true,
                FAILED_AT));
        assertFalse(publisherCalled.get());
    }

    private record StoreCall(
            WorkflowRunId runId,
            String errorCode,
            String safeMessage,
            Instant failedAt) {
    }
}
