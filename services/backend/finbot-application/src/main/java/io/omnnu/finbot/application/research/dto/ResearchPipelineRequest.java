package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.util.Objects;

public record ResearchPipelineRequest(
        StartWorkflowCommand workflowCommand,
        ResearchTaskMode taskMode,
        int attemptNumber,
        int maximumAttempts,
        MarketAnalysisScope marketAnalysisScope,
        WorkflowVersionId demoWorkflowVersionId) {
    public ResearchPipelineRequest(
            StartWorkflowCommand workflowCommand,
            ResearchTaskMode taskMode,
            int attemptNumber,
            int maximumAttempts) {
        this(workflowCommand, taskMode, attemptNumber, maximumAttempts, null, null);
    }

    public ResearchPipelineRequest(
            StartWorkflowCommand workflowCommand,
            ResearchTaskMode taskMode,
            int attemptNumber,
            int maximumAttempts,
            MarketAnalysisScope marketAnalysisScope) {
        this(workflowCommand, taskMode, attemptNumber, maximumAttempts, marketAnalysisScope, null);
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
