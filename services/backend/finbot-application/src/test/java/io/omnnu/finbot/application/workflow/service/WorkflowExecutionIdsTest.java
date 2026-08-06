package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelInputHash;
import io.omnnu.finbot.domain.debate.DecisionPanelFrozenInput;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkflowExecutionIdsTest {
    @Test
    void includesPanelIdentityInSessionDecisionAndMessageIds() {
        var runId = new WorkflowRunId("run_panel_identity_test");
        var research = WorkflowExecutionIds.debate(runId, DecisionPanelKey.RESEARCH);
        var execution = WorkflowExecutionIds.debate(runId, new DecisionPanelKey("execution"));

        assertNotEquals(research, execution);
        assertEquals(research, WorkflowExecutionIds.debate(runId, DecisionPanelKey.RESEARCH));
        assertNotEquals(
                WorkflowExecutionIds.decision(research),
                WorkflowExecutionIds.decision(execution));
        var researchSession = session(runId, research, DecisionPanelKey.RESEARCH, "a".repeat(64));
        var executionSession = session(
                runId, execution, new DecisionPanelKey("execution"), "b".repeat(64));
        assertNotEquals(
                WorkflowExecutionIds.message(researchSession, new WorkflowNodeId("node_shared_test"), 1),
                WorkflowExecutionIds.message(executionSession, new WorkflowNodeId("node_shared_test"), 1));
    }

    private static DebateSession session(
            WorkflowRunId runId,
            io.omnnu.finbot.domain.workflow.DebateId debateId,
            DecisionPanelKey panelKey,
            String inputHash) {
        return new DebateSession(
                debateId,
                runId,
                panelKey,
                panelKey.equals(DecisionPanelKey.RESEARCH)
                        ? DecisionPanelPurpose.RESEARCH
                        : DecisionPanelPurpose.EXECUTION,
                new DecisionPanelInputHash(inputHash),
                new DecisionPanelFrozenInput("{}"),
                DebateStatus.RUNNING,
                1,
                0,
                new WorkflowNodeId("node_shared_test"),
                Instant.parse("2026-08-04T00:00:00Z"),
                null,
                0);
    }
}
