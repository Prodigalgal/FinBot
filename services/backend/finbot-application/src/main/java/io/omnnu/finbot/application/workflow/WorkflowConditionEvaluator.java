package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.BooleanConditionOperand;
import io.omnnu.finbot.domain.workflow.DecimalConditionOperand;
import io.omnnu.finbot.domain.workflow.TextConditionOperand;
import io.omnnu.finbot.domain.workflow.TextListConditionOperand;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCondition;
import io.omnnu.finbot.domain.workflow.WorkflowConditionOperator;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class WorkflowConditionEvaluator {

    boolean isActive(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            int round,
            List<AgentMessage> messages) {
        var version = execution.definitionVersion();
        var incoming = version.edges().stream()
                .filter(edge -> !edge.loopEdge())
                .filter(edge -> edge.targetNodeId().equals(node.nodeId()))
                .toList();
        if (incoming.isEmpty()) {
            return true;
        }
        var allEdges = incoming.stream()
                .filter(edge -> edge.activationMode() == WorkflowActivationMode.ALL)
                .toList();
        var anyEdges = incoming.stream()
                .filter(edge -> edge.activationMode() == WorkflowActivationMode.ANY)
                .toList();
        return allEdges.stream().allMatch(edge -> passes(execution, version, edge, round, messages))
                && (anyEdges.isEmpty()
                        || anyEdges.stream().anyMatch(edge -> passes(execution, version, edge, round, messages)));
    }

    boolean passes(
            WorkflowExecutionContext execution,
            WorkflowEdgeDefinition edge,
            int round,
            List<AgentMessage> messages) {
        return passes(execution, execution.definitionVersion(), edge, round, messages);
    }

    private boolean passes(
            WorkflowExecutionContext execution,
            WorkflowDefinitionVersion version,
            WorkflowEdgeDefinition edge,
            int round,
            List<AgentMessage> messages) {
        var source = version.nodes().stream()
                .filter(node -> node.nodeId().equals(edge.sourceNodeId()))
                .findFirst()
                .orElseThrow();
        var message = latestMessage(edge, round, messages);
        if (source.enabled() && source.nodeType().llmBacked() && message == null) {
            return false;
        }
        if (edge.condition() == null) {
            return true;
        }
        var context = new LinkedHashMap<String, Object>();
        context.put("input", Map.of(
                "request_summary", execution.requestSummary(),
                "research_context", execution.researchContext()));
        context.put("current", message == null ? Map.of() : messageContext(message));
        context.put("state", Map.of("round", round));
        return evaluate(edge.condition(), context);
    }

    private static AgentMessage latestMessage(
            WorkflowEdgeDefinition edge,
            int round,
            List<AgentMessage> messages) {
        return messages.stream()
                .filter(message -> message.nodeId().equals(edge.sourceNodeId()))
                .filter(message -> message.roundIndex() <= round)
                .filter(message -> message.status() == AgentMessageStatus.COMPLETED)
                .max(Comparator.comparingInt(AgentMessage::roundIndex)
                        .thenComparingInt(AgentMessage::turnIndex))
                .orElse(null);
    }

    private static Map<String, Object> messageContext(AgentMessage message) {
        var content = message.content();
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", content.summary());
        result.put("argument", content.argument());
        result.put("confidence", content.confidence());
        result.put("evidence_refs", content.evidenceReferences());
        result.put("challenges", content.challenges());
        result.put("revision_notes", content.revisionNotes());
        result.put("status", message.status().name());
        result.put("round", message.roundIndex());
        return Map.copyOf(result);
    }

    static boolean evaluate(WorkflowCondition condition, Map<String, Object> context) {
        Objects.requireNonNull(condition, "condition");
        try {
            var actual = resolve(context, condition.field());
            return compare(actual, condition.operator(), operandValue(condition));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Object resolve(Map<String, Object> context, String field) {
        Object current = context;
        for (var segment : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    private static Object operandValue(WorkflowCondition condition) {
        return switch (condition.operand()) {
            case null -> null;
            case TextConditionOperand value -> value.value();
            case DecimalConditionOperand value -> value.value();
            case BooleanConditionOperand value -> value.value();
            case TextListConditionOperand value -> value.values();
        };
    }

    private static boolean compare(
            Object actual,
            WorkflowConditionOperator operator,
            Object expected) {
        return switch (operator) {
            case EXISTS -> actual != null;
            case TRUTHY -> truthy(actual);
            case FALSY -> !truthy(actual);
            case EQ -> equal(actual, expected);
            case NE -> !equal(actual, expected);
            case IN -> expected instanceof Collection<?> values && contains(values, actual);
            case NOT_IN -> expected instanceof Collection<?> values && !contains(values, actual);
            case GT -> decimal(actual).compareTo(decimal(expected)) > 0;
            case GTE -> decimal(actual).compareTo(decimal(expected)) >= 0;
            case LT -> decimal(actual).compareTo(decimal(expected)) < 0;
            case LTE -> decimal(actual).compareTo(decimal(expected)) <= 0;
            case CONTAINS -> containsValue(actual, expected);
        };
    }

    private static boolean equal(Object actual, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            return decimal(actual).compareTo(decimal(expected)) == 0;
        }
        return Objects.equals(actual, expected)
                || actual != null && expected != null
                        && actual.toString().equals(expected.toString());
    }

    private static boolean contains(Collection<?> values, Object expected) {
        return values.stream().anyMatch(value -> equal(value, expected));
    }

    private static boolean containsValue(Object actual, Object expected) {
        if (actual instanceof Collection<?> values) {
            return contains(values, expected);
        }
        return actual != null && expected != null && actual.toString().contains(expected.toString());
    }

    private static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean booleanValue -> booleanValue;
            case Number number -> decimal(number).compareTo(BigDecimal.ZERO) != 0;
            case CharSequence text -> !text.isEmpty();
            case Collection<?> values -> !values.isEmpty();
            default -> true;
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(Objects.requireNonNull(value, "decimal value").toString());
    }
}
