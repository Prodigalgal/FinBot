package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;

public interface WorkflowEventPublisher {
    WorkflowEvent publish(WorkflowRunId runId, WorkflowEventFactory eventFactory);
}
