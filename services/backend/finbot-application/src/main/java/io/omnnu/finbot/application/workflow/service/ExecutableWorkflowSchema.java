package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.util.List;

public final class ExecutableWorkflowSchema {
    private ExecutableWorkflowSchema() {
    }

    public static List<WorkflowNodeType> nodeTypes() {
        return WorkflowPublicationValidator.executableNodeTypes();
    }
}
