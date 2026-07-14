package io.omnnu.finbot.infrastructure.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.operations.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
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
                "instant-research:01j0000000001",
                ResearchTaskMode.RESUME_FAILED);

        var encoded = codec.encode(payload);
        var decoded = codec.decode(BackgroundTaskType.INSTANT_RESEARCH, encoded);

        assertEquals(payload, decoded);
        assertTrue(encoded.contains("\"taskMode\":\"RESUME_FAILED\""));
    }
}
