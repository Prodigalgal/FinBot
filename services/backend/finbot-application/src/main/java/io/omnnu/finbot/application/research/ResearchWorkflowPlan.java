package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ResearchWorkflowPlan(
        boolean collectEvidence,
        boolean compressEvidence,
        boolean runQuantResearch,
        boolean validateWithPaperTrading) {

    public static ResearchWorkflowPlan from(WorkflowDefinitionVersion version) {
        Objects.requireNonNull(version, "version");
        var ordered = version.topologicalNodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .toList();
        var positions = positions(ordered);
        requireAtMostOne(ordered, WorkflowNodeType.COLLECTOR);
        requireAtMostOne(ordered, WorkflowNodeType.CLEANER);
        requireAtMostOne(ordered, WorkflowNodeType.COMPRESSOR);
        requireAtMostOne(ordered, WorkflowNodeType.QUANT);

        var collector = positions.containsKey(WorkflowNodeType.COLLECTOR);
        var cleaner = positions.containsKey(WorkflowNodeType.CLEANER);
        if (collector != cleaner) {
            throw new IllegalStateException(
                    "COLLECTOR and CLEANER must be enabled or disabled together because collection persists normalized evidence atomically");
        }
        requireBefore(positions, WorkflowNodeType.COLLECTOR, WorkflowNodeType.CLEANER);
        if (positions.containsKey(WorkflowNodeType.COMPRESSOR)) {
            if (!cleaner) {
                throw new IllegalStateException("COMPRESSOR requires enabled COLLECTOR and CLEANER nodes");
            }
            requireBefore(positions, WorkflowNodeType.CLEANER, WorkflowNodeType.COMPRESSOR);
        }
        if (positions.containsKey(WorkflowNodeType.QUANT)
                && positions.containsKey(WorkflowNodeType.COMPRESSOR)) {
            requireBefore(positions, WorkflowNodeType.COMPRESSOR, WorkflowNodeType.QUANT);
        }
        var firstDebateNode = ordered.stream()
                .filter(node -> node.nodeType() == WorkflowNodeType.AGENT
                        || node.nodeType() == WorkflowNodeType.AGGREGATOR
                        || node.nodeType() == WorkflowNodeType.CHAIR)
                .mapToInt(ordered::indexOf)
                .min()
                .orElse(Integer.MAX_VALUE);
        positions.forEach((nodeType, position) -> {
            if (isPreparationNode(nodeType) && position >= firstDebateNode) {
                throw new IllegalStateException(
                        "Preparation node " + nodeType + " must appear before debate and chair nodes");
            }
        });
        return new ResearchWorkflowPlan(
                collector,
                positions.containsKey(WorkflowNodeType.COMPRESSOR),
                positions.containsKey(WorkflowNodeType.QUANT),
                positions.containsKey(WorkflowNodeType.EXECUTION_REVIEW));
    }

    private static Map<WorkflowNodeType, Integer> positions(List<WorkflowNodeDefinition> nodes) {
        var result = new EnumMap<WorkflowNodeType, Integer>(WorkflowNodeType.class);
        for (var index = 0; index < nodes.size(); index++) {
            result.putIfAbsent(nodes.get(index).nodeType(), index);
        }
        return Map.copyOf(result);
    }

    private static void requireAtMostOne(
            List<WorkflowNodeDefinition> nodes,
            WorkflowNodeType nodeType) {
        var count = nodes.stream().filter(node -> node.nodeType() == nodeType).count();
        if (count > 1) {
            throw new IllegalStateException(
                    "Workflow contains multiple enabled " + nodeType + " nodes but only one durable stage handler exists");
        }
    }

    private static void requireBefore(
            Map<WorkflowNodeType, Integer> positions,
            WorkflowNodeType first,
            WorkflowNodeType second) {
        var firstPosition = positions.get(first);
        var secondPosition = positions.get(second);
        if (firstPosition != null && secondPosition != null && firstPosition >= secondPosition) {
            throw new IllegalStateException(first + " must precede " + second + " in the workflow graph");
        }
    }

    private static boolean isPreparationNode(WorkflowNodeType nodeType) {
        return nodeType == WorkflowNodeType.COLLECTOR
                || nodeType == WorkflowNodeType.CLEANER
                || nodeType == WorkflowNodeType.COMPRESSOR
                || nodeType == WorkflowNodeType.QUANT;
    }
}
