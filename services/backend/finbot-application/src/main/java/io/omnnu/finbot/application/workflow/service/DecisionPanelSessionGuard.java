package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;

final class DecisionPanelSessionGuard {
    private DecisionPanelSessionGuard() {
    }

    static DebateSession requireCompatible(
            DebateSession session,
            DecisionPanelKey panelKey,
            DecisionPanelPurpose purpose,
            WorkflowNodeId decisionNodeId) {
        if (!session.panelKey().equals(panelKey)
                || session.panelPurpose() != purpose
                || !session.decisionNodeId().equals(decisionNodeId)) {
            throw new SdbScaExecutionException(
                    "DECISION_PANEL_SEED_MISMATCH",
                    "Persisted decision panel identity does not match the immutable workflow definition",
                    false);
        }
        return session;
    }
}
