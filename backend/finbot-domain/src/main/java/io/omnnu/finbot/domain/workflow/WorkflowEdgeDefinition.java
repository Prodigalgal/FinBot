package io.omnnu.finbot.domain.workflow;

import java.util.Objects;

public record WorkflowEdgeDefinition(
        WorkflowEdgeId edgeId,
        WorkflowNodeId sourceNodeId,
        WorkflowNodeId targetNodeId,
        WorkflowActivationMode activationMode,
        WorkflowEdgeContextMode contextMode,
        WorkflowCondition condition,
        boolean loopEdge,
        Integer maximumTraversals) {
    public WorkflowEdgeDefinition {
        Objects.requireNonNull(edgeId, "edgeId");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(activationMode, "activationMode");
        Objects.requireNonNull(contextMode, "contextMode");
        if (sourceNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("Workflow self edges are prohibited");
        }
        if (loopEdge) {
            Objects.requireNonNull(condition, "Loop edge condition");
            if (maximumTraversals == null || maximumTraversals < 1 || maximumTraversals > 8) {
                throw new IllegalArgumentException("Loop maximumTraversals must be between 1 and 8");
            }
        } else if (maximumTraversals != null) {
            throw new IllegalArgumentException("Non-loop edge must not define maximumTraversals");
        }
    }
}
