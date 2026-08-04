package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.domain.debate.DecisionPanelInputHash;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import java.util.Objects;

final class DecisionPanelSessionGuard {
    private DecisionPanelSessionGuard() {
    }

    static DebateSession requireCompatible(
            DebateSession session,
            DecisionPanelKey panelKey,
            DecisionPanelPurpose purpose,
            DecisionPanelInputHash inputHash,
            WorkflowNodeId decisionNodeId) {
        if (!session.panelKey().equals(panelKey)
                || session.panelPurpose() != purpose
                || !session.decisionNodeId().equals(decisionNodeId)) {
            throw new SdbScaExecutionException(
                    "DECISION_PANEL_SEED_MISMATCH",
                    "Persisted decision panel identity does not match the immutable workflow definition",
                    false);
        }
        // Migrated sessions have no trustworthy frozen-input hash and remain readable for recovery.
        if (session.inputHash() != null && !Objects.equals(session.inputHash(), inputHash)) {
            throw new SdbScaExecutionException(
                    "DECISION_PANEL_INPUT_MISMATCH",
                    "Persisted decision panel input differs from the frozen workflow input",
                    false);
        }
        return session;
    }
}
