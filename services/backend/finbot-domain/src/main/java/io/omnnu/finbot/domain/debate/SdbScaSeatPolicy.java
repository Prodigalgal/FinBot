package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class SdbScaSeatPolicy {
    private static final Set<String> ROUTING_SELECTORS = Set.of(
            "auto", "best", "chat", "default", "expert", "fast", "latest", "max", "random", "thinking");
    private static final Pattern SERVING_MODE = Pattern.compile(
            "-(?:low|medium|high|xhigh|max|thinking|reasoning|highspeed|deepsearch|search|heavy)$");
    private static final Pattern DYNAMIC_SELECTOR = Pattern.compile(
            "(?:^|[-.])(?:auto|random|latest)(?:[-.]|$)");

    private SdbScaSeatPolicy() {
    }

    public static void validate(List<WorkflowNodeDefinition> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        var models = new HashMap<RoleModel, WorkflowNodeId>();
        for (var node : nodes) {
            if (!node.enabled() || !participant(node)) {
                continue;
            }
            reserveModel(node.primaryAiBinding(), node, models);
            if (node.fallbackAiBinding() != null) {
                reserveModel(node.fallbackAiBinding(), node, models);
            }
        }
    }

    public static String canonicalModelName(String modelName) {
        var normalized = Objects.requireNonNull(modelName, "modelName")
                .strip().toLowerCase(Locale.ROOT).replace('_', '-');
        var lastSeparator = normalized.lastIndexOf('/');
        var upstreamName = normalized.substring(lastSeparator + 1);
        if (upstreamName.isBlank() || ROUTING_SELECTORS.contains(upstreamName)
                || DYNAMIC_SELECTOR.matcher(upstreamName).find()
                || upstreamName.startsWith("anonymous-") || upstreamName.endsWith("-expert")) {
            throw new IllegalArgumentException(
                    "SDB-SCA requires an explicit model ID instead of a routing selector: " + modelName);
        }
        // Provider namespaces and serving/reasoning modes do not establish model diversity.
        var canonical = upstreamName;
        while (SERVING_MODE.matcher(canonical).find()) {
            canonical = SERVING_MODE.matcher(canonical).replaceFirst("");
        }
        return canonical;
    }

    private static void reserveModel(
            AiModelBinding binding,
            WorkflowNodeDefinition node,
            Map<RoleModel, WorkflowNodeId> models) {
        var modelName = canonicalModelName(binding.modelName());
        var roleModel = new RoleModel(node.logicalRoleKey(), modelName);
        var previousSeat = models.putIfAbsent(roleModel, node.nodeId());
        if (previousSeat != null && !previousSeat.equals(node.nodeId())) {
            throw new IllegalArgumentException(
                    "SDB-SCA seats within one logical role must use distinct primary/fallback models: "
                            + node.logicalRoleKey().value() + "/" + modelName
                            + " (" + previousSeat.value() + ", " + node.nodeId().value() + ")");
        }
    }

    private static boolean participant(WorkflowNodeDefinition node) {
        return node.nodeType() == WorkflowNodeType.AGENT || node.nodeType() == WorkflowNodeType.AGGREGATOR;
    }

    private record RoleModel(LogicalRoleKey role, String modelName) {
    }
}
