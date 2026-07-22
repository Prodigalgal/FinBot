package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.concurrent.Flow;

@FunctionalInterface
public interface WorkflowEventStream {
    Flow.Publisher<WorkflowEvent> stream(WorkflowRunId runId, long afterSequence);
}
