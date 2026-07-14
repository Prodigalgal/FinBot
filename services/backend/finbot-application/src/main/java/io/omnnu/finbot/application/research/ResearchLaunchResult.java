package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import java.util.Objects;

public record ResearchLaunchResult(StartWorkflowResult workflow, BackgroundTask task) {
    public ResearchLaunchResult {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(task, "task");
    }
}
