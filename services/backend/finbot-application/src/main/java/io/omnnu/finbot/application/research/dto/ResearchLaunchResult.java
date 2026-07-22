package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import java.util.Objects;

public record ResearchLaunchResult(StartWorkflowResult workflow, BackgroundTask task) {
    public ResearchLaunchResult {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(task, "task");
    }
}
