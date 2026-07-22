package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.port.out.WorkflowCommandStore;
import io.omnnu.finbot.application.workflow.service.WorkflowRunApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowRunApplicationServiceTest {
    @Test
    void atomicallyPersistsAcceptedRunAndOutbox() {
        var calls = new ArrayList<String>();
        var sequence = new AtomicInteger();
        SortableIdGenerator ids = prefix -> prefix + "01j0000" + sequence.incrementAndGet();
        WorkflowCommandStore store = (command, event) -> {
            calls.add("store:" + event.eventId().value());
            return new StartWorkflowResult(event.runId(), event.eventId(), event.occurredAt());
        };
        var service = new WorkflowRunApplicationService(
                ids,
                store,
                Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC),
                Runnable::run);

        var result = service.start(new StartWorkflowCommand(
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.MANUAL,
                null,
                "Analyze BTC liquidity",
                "instant:test-1")).toCompletableFuture().join();

        assertEquals("run_01j00001", result.runId().value());
        assertEquals(
                java.util.List.of("store:event_01j00002"),
                calls);
    }
}
