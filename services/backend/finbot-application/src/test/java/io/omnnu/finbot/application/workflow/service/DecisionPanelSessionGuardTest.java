package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.domain.debate.DecisionPanelInputHash;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DecisionPanelSessionGuardTest {
    private static final WorkflowNodeId DECISION_NODE = new WorkflowNodeId("node_panel_guard_test");
    private static final DecisionPanelInputHash EXPECTED_HASH =
            new DecisionPanelInputHash("a".repeat(64));

    @Test
    void rejectsAChangedFrozenInput() {
        var failure = assertThrows(SdbScaExecutionException.class, () ->
                DecisionPanelSessionGuard.requireCompatible(
                        session(new DecisionPanelInputHash("b".repeat(64))),
                        DecisionPanelKey.RESEARCH,
                        DecisionPanelPurpose.RESEARCH,
                        EXPECTED_HASH,
                        DECISION_NODE));

        assertEquals("DECISION_PANEL_INPUT_MISMATCH", failure.errorCode());
    }

    @Test
    void permitsMigratedSessionWithoutInventingAnInputHash() {
        var session = session(null);

        assertEquals(session, DecisionPanelSessionGuard.requireCompatible(
                session,
                DecisionPanelKey.RESEARCH,
                DecisionPanelPurpose.RESEARCH,
                EXPECTED_HASH,
                DECISION_NODE));
    }

    private static DebateSession session(DecisionPanelInputHash inputHash) {
        return new DebateSession(
                new DebateId("debate_panel_guard_test"),
                new WorkflowRunId("run_panel_guard_test"),
                DecisionPanelKey.RESEARCH,
                DecisionPanelPurpose.RESEARCH,
                inputHash,
                DebateStatus.RUNNING,
                1,
                0,
                DECISION_NODE,
                Instant.parse("2026-08-04T00:00:00Z"),
                null,
                0);
    }
}
