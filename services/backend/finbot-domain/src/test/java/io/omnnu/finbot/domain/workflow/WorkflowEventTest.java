package io.omnnu.finbot.domain.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkflowEventTest {
    @Test
    void sealedEventProvidesStableEventType() {
        WorkflowEvent event = new WorkflowAccepted(
                new WorkflowEventId("event_01j00000"),
                new WorkflowRunId("run_01j00000"),
                1,
                WorkflowType.INSTANT_RESEARCH,
                Instant.parse("2026-07-13T12:00:00Z"));

        assertEquals("workflow.accepted", event.eventType());
    }

    @Test
    void progressIsBounded() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowProgressed(
                        new WorkflowEventId("event_01j00001"),
                        new WorkflowRunId("run_01j00001"),
                        2,
                        WorkflowStage.ANALYZE,
                        new WorkflowNodeId("node_01j00001"),
                        101,
                        "invalid progress",
                        Instant.now()));
    }
}
