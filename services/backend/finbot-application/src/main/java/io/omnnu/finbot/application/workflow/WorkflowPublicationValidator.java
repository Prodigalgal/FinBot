package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.application.quant.QuantAnalysisCapabilities;
import io.omnnu.finbot.application.research.ResearchWorkflowPlan;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class WorkflowPublicationValidator {
    private static final Set<WorkflowNodeType> EXECUTABLE_NODE_TYPES = EnumSet.of(
            WorkflowNodeType.INPUT,
            WorkflowNodeType.COLLECTOR,
            WorkflowNodeType.CLEANER,
            WorkflowNodeType.COMPRESSOR,
            WorkflowNodeType.QUANT,
            WorkflowNodeType.AGENT,
            WorkflowNodeType.AGGREGATOR,
            WorkflowNodeType.CHAIR,
            WorkflowNodeType.EXECUTION_REVIEW,
            WorkflowNodeType.OUTPUT);
    private static final Set<String> QUANT_OPERATIONS = Stream.concat(
                    Stream.of("statistical_analysis"),
                    QuantAnalysisCapabilities.strategies().stream()
                            .map(QuantAnalysisCapabilities.Capability::id))
            .collect(Collectors.toUnmodifiableSet());

    private WorkflowPublicationValidator() {
    }

    static void validate(WorkflowDefinitionVersion version) {
        Objects.requireNonNull(version, "version");
        ResearchWorkflowPlan.from(version);
        var unsupported = version.nodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .map(WorkflowNodeDefinition::nodeType)
                .filter(type -> !EXECUTABLE_NODE_TYPES.contains(type))
                .distinct()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                    "Workflow contains node types without a Java runtime executor: " + unsupported);
        }
        version.nodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .forEach(WorkflowPublicationValidator::validateNodeContract);
        validateExecutionReviewPair(version.nodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType() == WorkflowNodeType.EXECUTION_REVIEW)
                .toList());
    }

    static List<WorkflowNodeType> executableNodeTypes() {
        return List.copyOf(EXECUTABLE_NODE_TYPES);
    }

    private static void validateNodeContract(WorkflowNodeDefinition node) {
        switch (node.nodeType()) {
            case INPUT -> requireOperation(node, "research_input");
            case COLLECTOR -> requireOperation(node, "collect_enabled_sources");
            case CLEANER -> requireOperation(node, "normalize_and_deduplicate");
            case QUANT -> requireOperation(node, QUANT_OPERATIONS);
            case OUTPUT -> requireOperation(node, "research_output");
            case COMPRESSOR -> requireContract(node, WorkflowOutputContract.RESEARCH_FINDINGS);
            case CHAIR -> requireContract(node, WorkflowOutputContract.CHAIR_VERDICT);
            case EXECUTION_REVIEW -> executionStage(node);
            case AGENT, AGGREGATOR -> {
                if (node.outputContract() != WorkflowOutputContract.DEBATE_ARGUMENT
                        && node.outputContract() != WorkflowOutputContract.RISK_ASSESSMENT
                        && node.outputContract() != WorkflowOutputContract.RESEARCH_FINDINGS) {
                    throw new IllegalArgumentException(
                            "Debate node " + node.nodeId().value()
                                    + " requires DEBATE_ARGUMENT, RISK_ASSESSMENT or RESEARCH_FINDINGS output");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Workflow node has no executable runtime contract: " + node.nodeType());
        }
    }

    private static void validateExecutionReviewPair(List<WorkflowNodeDefinition> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        var draftCount = nodes.stream().filter(node -> executionStage(node).equals("DRAFT")).count();
        var reflectionCount = nodes.stream().filter(node -> executionStage(node).equals("REFLECTION")).count();
        if (draftCount != 1 || reflectionCount != 1) {
            throw new IllegalArgumentException(
                    "Workflow execution review requires exactly one draft and one reflection node");
        }
    }

    private static String executionStage(WorkflowNodeDefinition node) {
        var operation = Objects.requireNonNullElse(node.operation(), "")
                .strip()
                .toUpperCase(Locale.ROOT);
        return switch (operation) {
            case "DRAFT", "EXECUTION_DRAFT" -> {
                requireContract(node, WorkflowOutputContract.TRADE_DECISIONS);
                yield "DRAFT";
            }
            case "REFLECTION", "EXECUTION_REFLECTION" -> {
                requireContract(node, WorkflowOutputContract.EXECUTION_VERDICT);
                yield "REFLECTION";
            }
            default -> throw new IllegalArgumentException(
                    "Execution review node requires operation draft or reflection: "
                            + node.nodeId().value());
        };
    }

    private static void requireOperation(WorkflowNodeDefinition node, String expected) {
        if (!expected.equals(node.operation())) {
            throw new IllegalArgumentException(
                    "Node " + node.nodeId().value() + " requires operation " + expected);
        }
    }

    private static void requireOperation(WorkflowNodeDefinition node, Set<String> supported) {
        if (!supported.contains(node.operation())) {
            throw new IllegalArgumentException(
                    "Node " + node.nodeId().value() + " requires one of operations " + supported);
        }
    }

    private static void requireContract(
            WorkflowNodeDefinition node,
            WorkflowOutputContract expected) {
        if (node.outputContract() != expected) {
            throw new IllegalArgumentException(
                    "Node " + node.nodeId().value() + " requires output contract " + expected);
        }
    }
}
