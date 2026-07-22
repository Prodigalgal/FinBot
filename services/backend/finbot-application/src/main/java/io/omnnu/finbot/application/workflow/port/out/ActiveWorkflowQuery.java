package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.util.List;

@FunctionalInterface
public interface ActiveWorkflowQuery {
    List<WorkflowVersionId> activePublishedVersionIds();
}
