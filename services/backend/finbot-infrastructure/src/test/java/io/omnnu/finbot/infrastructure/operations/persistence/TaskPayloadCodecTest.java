package io.omnnu.finbot.infrastructure.operations.persistence;

import io.omnnu.finbot.infrastructure.operations.persistence.TaskPayloadCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.dto.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.research.dto.ResearchExecutionScope;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import org.junit.jupiter.api.Test;

class TaskPayloadCodecTest {
    private final TaskPayloadCodec codec = new TaskPayloadCodec(new ObjectMapper());

    @Test
    void preservesExplicitFailedWorkflowResumeMode() {
        var payload = new InstantResearchTaskPayload(
                "run_01j0000000001",
                "Resume the failed research run",
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.API,
                new WorkflowVersionId("workflowversion_01j0000000001"),
                new WorkflowVersionId("workflowversion_01j0000000002"),
                "instant-research:01j0000000001",
                ResearchTaskMode.RESUME_FAILED,
                null);

        var encoded = codec.encode(payload);
        var decoded = codec.decode(BackgroundTaskType.INSTANT_RESEARCH, encoded);

        assertEquals(payload, decoded);
        assertTrue(encoded.contains("\"taskMode\":\"RESUME_FAILED\""));
        assertFalse(encoded.contains("executionScope"));
    }

    @Test
    void preservesTypedMarketAnalysisScope() {
        var payload = new InstantResearchTaskPayload(
                "run_01j0000000002",
                "Analyze Gate ETHUSDT at 15 minute resolution",
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.API,
                new WorkflowVersionId("workflowversion_01j0000000001"),
                null,
                "market-analysis:01j0000000002",
                ResearchTaskMode.STANDARD,
                new MarketAnalysisScope(
                        new InstrumentId("instrument_gate_ethusdt"),
                        "ETHUSDT",
                        ExchangeVenue.GATE,
                        ExchangeEnvironment.LIVE,
                        900,
                        86_400));

        var decoded = codec.decode(
                BackgroundTaskType.INSTANT_RESEARCH,
                codec.encode(payload));

        assertEquals(payload, decoded);
    }

    @Test
    void analysisScopeSurvivesQueueRoundTripAndOldTasksDefaultToFull() throws Exception {
        var payload = new InstantResearchTaskPayload(
                "run_01j0000000003",
                "Analyze without trading",
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.API,
                new WorkflowVersionId("workflowversion_01j0000000001"),
                null,
                "analysis-chat:01j0000000003",
                ResearchTaskMode.STANDARD,
                null,
                ResearchExecutionScope.ANALYSIS_ONLY);

        var encoded = codec.encode(payload);
        assertEquals(payload, codec.decode(BackgroundTaskType.INSTANT_RESEARCH, encoded));
        assertTrue(encoded.contains("\"executionScope\":\"ANALYSIS_ONLY\""));

        var mapper = new ObjectMapper();
        var legacyNode = (ObjectNode) mapper.readTree(encoded);
        legacyNode.remove("executionScope");
        var legacyJson = mapper.writeValueAsString(legacyNode);
        var legacy = (InstantResearchTaskPayload) codec.decode(BackgroundTaskType.INSTANT_RESEARCH, legacyJson);
        assertEquals(ResearchExecutionScope.FULL, legacy.executionScope());
    }
}
