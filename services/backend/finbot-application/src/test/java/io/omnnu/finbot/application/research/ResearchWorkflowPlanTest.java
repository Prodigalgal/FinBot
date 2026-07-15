package io.omnnu.finbot.application.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeId;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResearchWorkflowPlanTest {
    private static final Instant NOW = Instant.parse("2026-07-15T04:00:00Z");

    @Test
    void derivesEnabledDurablePreparationStagesFromPublishedGraph() {
        var plan = ResearchWorkflowPlan.from(version(true, true, true, true));

        assertTrue(plan.collectEvidence());
        assertTrue(plan.compressEvidence());
        assertTrue(plan.runQuantResearch());
    }

    @Test
    void permitsARequestOnlyDebateWithoutPreparationStages() {
        var plan = ResearchWorkflowPlan.from(version(false, false, false, false));

        assertFalse(plan.collectEvidence());
        assertFalse(plan.compressEvidence());
        assertFalse(plan.runQuantResearch());
    }

    @Test
    void rejectsHalfEnabledAtomicIngestionPipeline() {
        var invalid = version(true, false, false, false);

        assertThrows(IllegalStateException.class, () -> ResearchWorkflowPlan.from(invalid));
    }

    private static WorkflowDefinitionVersion version(
            boolean collectorEnabled,
            boolean cleanerEnabled,
            boolean compressorEnabled,
            boolean quantEnabled) {
        var input = node("node_input", WorkflowNodeType.INPUT, true);
        var collector = node("node_collector", WorkflowNodeType.COLLECTOR, collectorEnabled);
        var cleaner = node("node_cleaner", WorkflowNodeType.CLEANER, cleanerEnabled);
        var compressor = node("node_compressor", WorkflowNodeType.COMPRESSOR, compressorEnabled);
        var quant = node("node_quant", WorkflowNodeType.QUANT, quantEnabled);
        var agent = node("node_agent", WorkflowNodeType.AGENT, true);
        var chair = node("node_chair", WorkflowNodeType.CHAIR, true);
        var output = node("node_output", WorkflowNodeType.OUTPUT, true);
        var nodes = List.of(input, collector, cleaner, compressor, quant, agent, chair, output);
        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_plan_test"),
                new WorkflowDefinitionId("workflow_plan_test"),
                1,
                WorkflowVersionStatus.PUBLISHED,
                3,
                50,
                Duration.ofMinutes(10),
                100_000,
                BigDecimal.TEN,
                WorkflowFailurePolicy.STOP,
                "b".repeat(64),
                NOW,
                NOW,
                "test",
                nodes,
                List.of(
                        edge("edge_input_collector", input, collector),
                        edge("edge_collector_cleaner", collector, cleaner),
                        edge("edge_cleaner_compressor", cleaner, compressor),
                        edge("edge_compressor_quant", compressor, quant),
                        edge("edge_quant_agent", quant, agent),
                        edge("edge_agent_chair", agent, chair),
                        edge("edge_chair_output", chair, output)));
    }

    private static WorkflowNodeDefinition node(
            String id,
            WorkflowNodeType type,
            boolean enabled) {
        var llm = type.llmBacked();
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                type,
                id,
                llm ? id : null,
                null,
                llm ? new AiModelBinding(
                        new AiProviderProfileId("provider_test"),
                        "model-test",
                        ReasoningEffort.HIGH) : null,
                null,
                llm ? "Return strict JSON." : null,
                llm ? "Analyze the supplied evidence." : null,
                llm ? outputContract(type) : null,
                type == WorkflowNodeType.INPUT ? WorkflowContextMode.NONE : WorkflowContextMode.UPSTREAM,
                llm ? 3 : 0,
                llm ? 16 : 0,
                512,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                operation(type),
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                enabled);
    }

    private static WorkflowOutputContract outputContract(WorkflowNodeType type) {
        return switch (type) {
            case COMPRESSOR -> WorkflowOutputContract.RESEARCH_FINDINGS;
            case CHAIR -> WorkflowOutputContract.CHAIR_VERDICT;
            default -> WorkflowOutputContract.DEBATE_ARGUMENT;
        };
    }

    private static String operation(WorkflowNodeType type) {
        return switch (type) {
            case INPUT -> "research_input";
            case COLLECTOR -> "collect_enabled_sources";
            case CLEANER -> "normalize_and_deduplicate";
            case QUANT -> "statistical_analysis";
            case OUTPUT -> "research_output";
            default -> null;
        };
    }

    private static WorkflowEdgeDefinition edge(
            String id,
            WorkflowNodeDefinition source,
            WorkflowNodeDefinition target) {
        return new WorkflowEdgeDefinition(
                new WorkflowEdgeId(id),
                source.nodeId(),
                target.nodeId(),
                WorkflowActivationMode.ALL,
                WorkflowEdgeContextMode.INCLUDE,
                null,
                false,
                null);
    }
}
