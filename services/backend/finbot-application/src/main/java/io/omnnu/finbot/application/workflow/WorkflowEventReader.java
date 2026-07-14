package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;

@FunctionalInterface
public interface WorkflowEventReader {
    List<WorkflowEvent> loadAfter(WorkflowRunId runId, long afterSequence, int limit);
}
