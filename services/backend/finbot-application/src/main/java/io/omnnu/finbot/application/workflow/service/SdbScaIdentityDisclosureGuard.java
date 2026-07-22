package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Rejects structured debate artifacts that disclose a configured seat identity. */
final class SdbScaIdentityDisclosureGuard {
    private final List<String> forbiddenTokens;

    SdbScaIdentityDisclosureGuard(List<WorkflowNodeDefinition> participants) {
        var tokens = new LinkedHashSet<String>();
        List.copyOf(Objects.requireNonNull(participants, "participants"))
                .forEach(node -> collect(tokens, node));
        forbiddenTokens = List.copyOf(tokens);
    }

    String requireAnonymous(String canonicalJson) {
        var content = Objects.requireNonNull(canonicalJson, "canonicalJson");
        var normalized = content.toLowerCase(Locale.ROOT);
        forbiddenTokens.stream()
                .filter(normalized::contains)
                .findFirst()
                .ifPresent(token -> {
                    throw new IllegalArgumentException(
                            "SDB-SCA artifact disclosed a configured seat identity");
                });
        return content;
    }

    private static void collect(LinkedHashSet<String> tokens, WorkflowNodeDefinition node) {
        add(tokens, node.nodeId().value());
        add(tokens, node.displayName());
        add(tokens, node.roleName());
        if (node.roleTemplateId() != null) {
            add(tokens, node.roleTemplateId().value());
        }
        if (node.logicalRoleKey() != null) {
            add(tokens, node.logicalRoleKey().value());
        }
        if (node.primaryAiBinding() != null) {
            add(tokens, node.primaryAiBinding().providerProfileId().value());
            add(tokens, node.primaryAiBinding().modelName());
        }
        if (node.fallbackAiBinding() != null) {
            add(tokens, node.fallbackAiBinding().providerProfileId().value());
            add(tokens, node.fallbackAiBinding().modelName());
        }
    }

    private static void add(LinkedHashSet<String> tokens, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        var normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() >= 3) {
            tokens.add(normalized);
        }
    }
}
