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
        requireAtMostOne(ordered, WorkflowNodeType.COMPRESSION_VALIDATOR);
        requireAtMostOne(ordered, WorkflowNodeType.QUANT);

        var collector = positions.containsKey(WorkflowNodeType.COLLECTOR);
        var cleaner = positions.containsKey(WorkflowNodeType.CLEANER);
        if (collector != cleaner) {
            throw new IllegalStateException(
                    "COLLECTOR and CLEANER must be enabled or disabled together because collection persists normalized evidence atomically");
        }
        requireBefore(positions, WorkflowNodeType.COLLECTOR, WorkflowNodeType.CLEANER);
        if (contains(ordered, WorkflowNodeType.AI_CLEANER)) {
            if (!cleaner) {
                throw new IllegalStateException("AI_CLEANER requires enabled COLLECTOR and CLEANER nodes");
            }
            requireAllBefore(ordered, WorkflowNodeType.CLEANER, WorkflowNodeType.AI_CLEANER);
        }
        if (positions.containsKey(WorkflowNodeType.COMPRESSOR)) {
            if (!cleaner) {
                throw new IllegalStateException("COMPRESSOR requires enabled COLLECTOR and CLEANER nodes");
            }
            requireAllBefore(ordered, WorkflowNodeType.CLEANER, WorkflowNodeType.COMPRESSOR);
            requireAllBefore(ordered, WorkflowNodeType.AI_CLEANER, WorkflowNodeType.COMPRESSOR);
        }
        if (contains(ordered, WorkflowNodeType.COMPRESSION_VALIDATOR)) {
            if (!contains(ordered, WorkflowNodeType.COMPRESSOR)) {
                throw new IllegalStateException("COMPRESSION_VALIDATOR requires at least one enabled COMPRESSOR node");
            }
            requireMinimum(ordered, WorkflowNodeType.AI_CLEANER, 2);
            requireMinimum(ordered, WorkflowNodeType.COMPRESSOR, 2);
            requireAllBefore(ordered, WorkflowNodeType.COMPRESSOR, WorkflowNodeType.COMPRESSION_VALIDATOR);
        }
        if (contains(ordered, WorkflowNodeType.QUANT)) {
            if (contains(ordered, WorkflowNodeType.COMPRESSION_VALIDATOR)) {
                requireAllBefore(ordered, WorkflowNodeType.COMPRESSION_VALIDATOR, WorkflowNodeType.QUANT);
            } else {
                requireAllBefore(ordered, WorkflowNodeType.COMPRESSOR, WorkflowNodeType.QUANT);
            }
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

    private static void requireMinimum(
            List<WorkflowNodeDefinition> nodes,
            WorkflowNodeType nodeType,
            int minimum) {
        var count = nodes.stream().filter(node -> node.nodeType() == nodeType).count();
        if (count < minimum) {
            throw new IllegalStateException(
                    "Workflow requires at least " + minimum + " enabled " + nodeType + " nodes");
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

    private static boolean contains(List<WorkflowNodeDefinition> nodes, WorkflowNodeType type) {
        return nodes.stream().anyMatch(node -> node.nodeType() == type);
    }

    private static void requireAllBefore(
            List<WorkflowNodeDefinition> nodes,
            WorkflowNodeType first,
            WorkflowNodeType second) {
        var firstPositions = java.util.stream.IntStream.range(0, nodes.size())
                .filter(index -> nodes.get(index).nodeType() == first)
                .boxed()
                .toList();
        var secondPositions = java.util.stream.IntStream.range(0, nodes.size())
                .filter(index -> nodes.get(index).nodeType() == second)
                .boxed()
                .toList();
        if (firstPositions.isEmpty() || secondPositions.isEmpty()) {
            return;
        }
        if (firstPositions.getLast() >= secondPositions.getFirst()) {
            throw new IllegalStateException(first + " nodes must precede " + second + " nodes");
        }
    }

    private static boolean isPreparationNode(WorkflowNodeType nodeType) {
        return nodeType == WorkflowNodeType.COLLECTOR
                || nodeType == WorkflowNodeType.CLEANER
                || nodeType == WorkflowNodeType.AI_CLEANER
                || nodeType == WorkflowNodeType.COMPRESSOR
                || nodeType == WorkflowNodeType.COMPRESSION_VALIDATOR
                || nodeType == WorkflowNodeType.QUANT;
    }
}
