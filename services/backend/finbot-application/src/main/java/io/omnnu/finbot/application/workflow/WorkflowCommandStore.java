package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowAccepted;

public interface WorkflowCommandStore {
    StartWorkflowResult accept(StartWorkflowCommand command, WorkflowAccepted acceptedEvent);
}
