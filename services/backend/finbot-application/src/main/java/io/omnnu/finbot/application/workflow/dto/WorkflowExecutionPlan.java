package io.omnnu.finbot.application.workflow.dto;

import java.util.List;

public record WorkflowExecutionPlan(
        String workflowVersionId,
        int defaultDebateRounds,
        int maximumDebateRounds,
        int maximumSteps,
        long maximumTokens,
        String maximumCostUsd,
        List<PlannedNode> nodes,
        List<String> warnings) {
    public WorkflowExecutionPlan {
        nodes = List.copyOf(nodes);
        warnings = List.copyOf(warnings);
    }

    public record PlannedNode(
            int sequence,
            String nodeId,
            String displayName,
            String nodeType,
            String runtimeHandler,
            String invocationPolicy,
            List<String> upstreamNodeIds,
            String providerProfileId,
            String modelName,
            String reasoningEffort,
            String fallbackProviderProfileId,
            String fallbackModelName,
            boolean enabled) {
        public PlannedNode {
            upstreamNodeIds = List.copyOf(upstreamNodeIds);
        }
    }
}
