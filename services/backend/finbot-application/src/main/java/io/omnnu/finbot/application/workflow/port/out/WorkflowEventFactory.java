package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import java.time.Instant;

@FunctionalInterface
public interface WorkflowEventFactory {
    WorkflowEvent create(WorkflowEventId eventId, long sequence, Instant occurredAt);
}
