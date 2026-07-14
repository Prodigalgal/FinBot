package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record WorkflowDefinitionVersion(
        WorkflowVersionId versionId,
        WorkflowDefinitionId definitionId,
        int versionNumber,
        WorkflowVersionStatus status,
        int defaultDebateRounds,
        int maximumSteps,
        Duration maximumDuration,
        long maximumTokens,
        BigDecimal maximumCostUsd,
        WorkflowFailurePolicy failurePolicy,
        String checksum,
        Instant publishedAt,
        Instant createdAt,
        String createdBy,
        List<WorkflowNodeDefinition> nodes,
        List<WorkflowEdgeDefinition> edges) {
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");

    public WorkflowDefinitionVersion {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(maximumDuration, "maximumDuration");
        maximumCostUsd = DecimalValue.nonNegative(maximumCostUsd, "maximumCostUsd");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        checksum = Objects.requireNonNull(checksum, "checksum").strip();
        Objects.requireNonNull(createdAt, "createdAt");
        createdBy = Objects.requireNonNull(createdBy, "createdBy").strip();
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        if (defaultDebateRounds < 1 || defaultDebateRounds > 8) {
            throw new IllegalArgumentException("defaultDebateRounds must be between 1 and 8");
        }
        if (maximumSteps < 1 || maximumSteps > 1_000) {
            throw new IllegalArgumentException("maximumSteps must be between 1 and 1000");
        }
        if (maximumDuration.compareTo(Duration.ofSeconds(10)) < 0
                || maximumDuration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("maximumDuration must be between ten seconds and one day");
        }
        if (maximumTokens < 1_000 || maximumTokens > 10_000_000) {
            throw new IllegalArgumentException("maximumTokens must be between 1000 and 10000000");
        }
        if (!CHECKSUM.matcher(checksum).matches()) {
            throw new IllegalArgumentException("Invalid workflow checksum");
        }
        if (status == WorkflowVersionStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("Published workflow must have publishedAt");
        }
        if (status == WorkflowVersionStatus.DRAFT && publishedAt != null) {
            throw new IllegalArgumentException("Draft workflow must not have publishedAt");
        }
        validateGraph(nodes, edges);
    }

    public List<WorkflowNodeDefinition> topologicalNodes() {
        return topological(nodes, edges);
    }

    public WorkflowNodeDefinition chair() {
        return nodes.stream()
                .filter(node -> node.nodeType() == WorkflowNodeType.CHAIR)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow has no chair"));
    }

    private static void validateGraph(
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdgeDefinition> edges) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Workflow must contain nodes");
        }
        var nodesById = uniqueIndex(nodes, WorkflowNodeDefinition::nodeId, "node");
        uniqueIndex(edges, WorkflowEdgeDefinition::edgeId, "edge");
        edges.forEach(edge -> {
            if (!nodesById.containsKey(edge.sourceNodeId()) || !nodesById.containsKey(edge.targetNodeId())) {
                throw new IllegalArgumentException("Workflow edge references an unknown node");
            }
        });
        var inputs = nodes.stream().filter(node -> node.nodeType() == WorkflowNodeType.INPUT).toList();
        var outputs = nodes.stream().filter(node -> node.nodeType() == WorkflowNodeType.OUTPUT).toList();
        if (inputs.size() != 1 || outputs.size() != 1) {
            throw new IllegalArgumentException("Workflow requires exactly one INPUT and one OUTPUT node");
        }
        var chairs = nodes.stream().filter(node -> node.nodeType() == WorkflowNodeType.CHAIR).toList();
        var agents = nodes.stream().filter(node -> node.nodeType() == WorkflowNodeType.AGENT).toList();
        if (!agents.isEmpty() && chairs.size() != 1) {
            throw new IllegalArgumentException("A workflow with AGENT nodes requires exactly one CHAIR");
        }
        topological(nodes, edges);
        requireReachability(inputs.getFirst().nodeId(), outputs.getFirst().nodeId(), nodes, edges);
        if (!agents.isEmpty()) {
            var chairId = chairs.getFirst().nodeId();
            var adjacency = adjacency(edges, false);
            for (var agent : agents) {
                if (!reachable(agent.nodeId(), chairId, adjacency)) {
                    throw new IllegalArgumentException("Every AGENT node must flow to the CHAIR");
                }
            }
        }
    }

    private static List<WorkflowNodeDefinition> topological(
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdgeDefinition> edges) {
        var indegree = nodes.stream().collect(Collectors.toMap(
                WorkflowNodeDefinition::nodeId,
                ignored -> 0));
        var adjacency = adjacency(edges, false);
        edges.stream().filter(edge -> !edge.loopEdge()).forEach(edge ->
                indegree.compute(edge.targetNodeId(), (ignored, value) -> Objects.requireNonNull(value) + 1));
        var ready = new java.util.PriorityQueue<WorkflowNodeId>(Comparator.comparing(WorkflowNodeId::value));
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });
        var byId = nodes.stream().collect(Collectors.toMap(WorkflowNodeDefinition::nodeId, Function.identity()));
        var ordered = new ArrayList<WorkflowNodeDefinition>(nodes.size());
        while (!ready.isEmpty()) {
            var nodeId = ready.remove();
            ordered.add(byId.get(nodeId));
            for (var target : adjacency.getOrDefault(nodeId, Set.of())) {
                var remaining = indegree.compute(target, (ignored, value) -> Objects.requireNonNull(value) - 1);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            throw new IllegalArgumentException("Non-loop workflow edges must be acyclic");
        }
        return List.copyOf(ordered);
    }

    private static void requireReachability(
            WorkflowNodeId input,
            WorkflowNodeId output,
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdgeDefinition> edges) {
        var adjacency = adjacency(edges, true);
        var reachableFromInput = traverse(input, adjacency);
        if (reachableFromInput.size() != nodes.size() || !reachableFromInput.contains(output)) {
            throw new IllegalArgumentException("Every workflow node must be reachable from INPUT");
        }
        var reverse = new HashMap<WorkflowNodeId, Set<WorkflowNodeId>>();
        adjacency.forEach((source, targets) -> targets.forEach(target ->
                reverse.computeIfAbsent(target, ignored -> new HashSet<>()).add(source)));
        var reachesOutput = traverse(output, reverse);
        if (reachesOutput.size() != nodes.size()) {
            throw new IllegalArgumentException("Every workflow node must reach OUTPUT");
        }
    }

    private static boolean reachable(
            WorkflowNodeId start,
            WorkflowNodeId target,
            Map<WorkflowNodeId, Set<WorkflowNodeId>> adjacency) {
        return traverse(start, adjacency).contains(target);
    }

    private static Set<WorkflowNodeId> traverse(
            WorkflowNodeId start,
            Map<WorkflowNodeId, Set<WorkflowNodeId>> adjacency) {
        var visited = new HashSet<WorkflowNodeId>();
        var queue = new ArrayDeque<WorkflowNodeId>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (visited.add(current)) {
                queue.addAll(adjacency.getOrDefault(current, Set.of()));
            }
        }
        return Set.copyOf(visited);
    }

    private static Map<WorkflowNodeId, Set<WorkflowNodeId>> adjacency(
            List<WorkflowEdgeDefinition> edges,
            boolean includeLoops) {
        var adjacency = new HashMap<WorkflowNodeId, Set<WorkflowNodeId>>();
        edges.stream().filter(edge -> includeLoops || !edge.loopEdge()).forEach(edge ->
                adjacency.computeIfAbsent(edge.sourceNodeId(), ignored -> new HashSet<>())
                        .add(edge.targetNodeId()));
        return adjacency;
    }

    private static <K, V> Map<K, V> uniqueIndex(
            List<V> values,
            Function<V, K> keyExtractor,
            String label) {
        var result = new HashMap<K, V>();
        for (var value : values) {
            var key = keyExtractor.apply(value);
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("Duplicate workflow " + label + ": " + key);
            }
        }
        return Map.copyOf(result);
    }
}
