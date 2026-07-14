package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

public record SaveWorkflowDraftCommand(
        WorkflowDefinitionId definitionId,
        WorkflowVersionId versionId,
        String name,
        String description,
        int defaultDebateRounds,
        int maximumSteps,
        Duration maximumDuration,
        long maximumTokens,
        BigDecimal maximumCostUsd,
        WorkflowFailurePolicy failurePolicy,
        String expectedChecksum,
        List<WorkflowNodeDefinition> nodes,
        List<WorkflowEdgeDefinition> edges) {
    public SaveWorkflowDraftCommand {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
