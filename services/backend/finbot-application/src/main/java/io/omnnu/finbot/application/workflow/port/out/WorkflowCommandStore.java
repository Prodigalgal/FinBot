package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;

import io.omnnu.finbot.domain.workflow.WorkflowAccepted;

public interface WorkflowCommandStore {
    StartWorkflowResult accept(StartWorkflowCommand command, WorkflowAccepted acceptedEvent);
}
