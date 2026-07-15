package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.market.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import java.util.Objects;

public record ResearchPipelineRequest(
        StartWorkflowCommand workflowCommand,
        ResearchTaskMode taskMode,
        int attemptNumber,
        int maximumAttempts,
        MarketAnalysisScope marketAnalysisScope) {
    public ResearchPipelineRequest(
            StartWorkflowCommand workflowCommand,
            ResearchTaskMode taskMode,
            int attemptNumber,
            int maximumAttempts) {
        this(workflowCommand, taskMode, attemptNumber, maximumAttempts, null);
    }

    public ResearchPipelineRequest {
        Objects.requireNonNull(workflowCommand, "workflowCommand");
        Objects.requireNonNull(taskMode, "taskMode");
        if (attemptNumber < 1 || maximumAttempts < 1 || attemptNumber > maximumAttempts) {
            throw new IllegalArgumentException("Invalid research pipeline attempt counters");
        }
    }

    public boolean finalAttempt() {
        return attemptNumber == maximumAttempts;
    }
}
