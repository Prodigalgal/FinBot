package io.omnnu.finbot.infrastructure.operations.persistence;

import io.omnnu.finbot.infrastructure.operations.persistence.TaskPayloadCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.dto.MarketAnalysisScope;
import io.omnnu.finbot.application.operations.dto.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
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
}
