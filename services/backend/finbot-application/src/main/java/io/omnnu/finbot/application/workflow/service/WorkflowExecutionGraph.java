package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

final class WorkflowExecutionGraph {
    private WorkflowExecutionGraph() {
    }

    static List<List<WorkflowNodeDefinition>> agentLayers(WorkflowDefinitionVersion version) {
        var orderedAgents = version.topologicalNodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType() == WorkflowNodeType.AGENT
                        || node.nodeType() == WorkflowNodeType.AGGREGATOR)
                .toList();
        var agentIds = orderedAgents.stream()
                .map(WorkflowNodeDefinition::nodeId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var levelByNode = new HashMap<WorkflowNodeId, Integer>();
        var layers = new TreeMap<Integer, List<WorkflowNodeDefinition>>();
        for (var agent : orderedAgents) {
            var level = version.edges().stream()
                    .filter(edge -> !edge.loopEdge())
                    .filter(edge -> edge.targetNodeId().equals(agent.nodeId()))
                    .filter(edge -> agentIds.contains(edge.sourceNodeId()))
                    .mapToInt(edge -> levelByNode.getOrDefault(edge.sourceNodeId(), 0) + 1)
                    .max()
                    .orElse(0);
            levelByNode.put(agent.nodeId(), level);
            layers.computeIfAbsent(level, ignored -> new ArrayList<>()).add(agent);
        }
        return layers.values().stream()
                .map(layer -> layer.stream()
                        .sorted(Comparator.comparing(node -> node.nodeId().value()))
                        .toList())
                .toList();
    }

    static int maximumDebateRounds(WorkflowDefinitionVersion version) {
        return version.defaultDebateRounds() + version.edges().stream()
                .filter(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::loopEdge)
                .map(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::maximumTraversals)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    static int latestRound(List<AgentMessage> messages) {
        return messages.stream().mapToInt(AgentMessage::roundIndex).max().orElse(0);
    }

    static Map<WorkflowNodeId, Integer> turnIndexes(List<WorkflowNodeDefinition> agents) {
        var indexes = new LinkedHashMap<WorkflowNodeId, Integer>();
        for (var index = 0; index < agents.size(); index++) {
            indexes.put(agents.get(index).nodeId(), index + 1);
        }
        return Map.copyOf(indexes);
    }

    static Optional<AgentMessage> findMessage(
            List<AgentMessage> messages,
            AgentMessageId messageId) {
        return messages.stream()
                .filter(message -> message.messageId().equals(messageId))
                .findFirst();
    }

    static String roleName(WorkflowNodeDefinition node) {
        return node.roleName() == null ? node.displayName() : node.roleName();
    }
}
