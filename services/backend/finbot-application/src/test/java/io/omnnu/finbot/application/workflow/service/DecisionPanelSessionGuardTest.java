package io.omnnu.finbot.application.workflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.domain.debate.DecisionPanelFrozenInput;
import io.omnnu.finbot.domain.debate.DecisionPanelInputHash;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DecisionPanelSessionGuardTest {
    private static final WorkflowNodeId DECISION_NODE = new WorkflowNodeId("node_panel_guard_test");
    private static final DecisionPanelInputHash EXPECTED_HASH =
            new DecisionPanelInputHash("a".repeat(64));

    @Test
    void rejectsAChangedPanelIdentity() {
        var failure = assertThrows(SdbScaExecutionException.class, () ->
                DecisionPanelSessionGuard.requireCompatible(
                        session(EXPECTED_HASH, new DecisionPanelFrozenInput("{}")),
                        DecisionPanelKey.RESEARCH,
                        DecisionPanelPurpose.PRINCIPAL_REVIEW,
                        DECISION_NODE));

        assertEquals("DECISION_PANEL_SEED_MISMATCH", failure.errorCode());
    }

    @Test
    void permitsMigratedSessionWithoutInventingAnInputHash() {
        var session = session(null, null);

        assertEquals(session, DecisionPanelSessionGuard.requireCompatible(
                session,
                DecisionPanelKey.RESEARCH,
                DecisionPanelPurpose.RESEARCH,
                DECISION_NODE));
    }

    @Test
    void restoresThePersistedFirstAttemptInputOnRetry() {
        var frozen = new DecisionPanelFrozenInput("{\"snapshot\":\"first-attempt\"}");
        var session = session(EXPECTED_HASH, frozen);
        var latest = new WorkflowExecutionContext(
                session.runId(),
                WorkflowRunStatus.RUNNING,
                "Analyze BTC liquidity",
                "{\"snapshot\":\"recollected\"}",
                null);

        var restored = DecisionPanelSessionService.restoreFrozenInput(latest, session);

        assertEquals(frozen.json(), restored.researchContext());
        assertEquals(latest.requestSummary(), restored.requestSummary());
        assertEquals(latest.runId(), restored.runId());
    }

    private static DebateSession session(
            DecisionPanelInputHash inputHash,
            DecisionPanelFrozenInput frozenInput) {
        return new DebateSession(
                new DebateId("debate_panel_guard_test"),
                new WorkflowRunId("run_panel_guard_test"),
                DecisionPanelKey.RESEARCH,
                DecisionPanelPurpose.RESEARCH,
                inputHash,
                frozenInput,
                DebateStatus.RUNNING,
                1,
                0,
                DECISION_NODE,
                Instant.parse("2026-08-04T00:00:00Z"),
                null,
                0);
    }
}
