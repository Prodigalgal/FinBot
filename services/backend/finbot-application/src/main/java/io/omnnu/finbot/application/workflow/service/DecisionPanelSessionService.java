package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.DecisionPanelSeedConflictException;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import java.time.Clock;
import java.util.Objects;

final class DecisionPanelSessionService {
    private final WorkflowExecutionStore executionStore;
    private final Clock clock;

    DecisionPanelSessionService(WorkflowExecutionStore executionStore, Clock clock) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    DebateSession ensure(
            WorkflowExecutionContext execution,
            DecisionPanelKey panelKey,
            DecisionPanelPurpose purpose,
            WorkflowNodeId decisionNodeId,
            int configuredRounds) {
        Objects.requireNonNull(execution, "execution");
        var inputHash = DecisionPanelInputHasher.hash(execution, panelKey, purpose);
        var existing = executionStore.findDebate(execution.runId(), panelKey);
        if (existing.isPresent()) {
            return DecisionPanelSessionGuard.requireCompatible(
                    existing.orElseThrow(), panelKey, purpose, inputHash, decisionNodeId);
        }
        var proposed = new DebateSession(
                WorkflowExecutionIds.debate(execution.runId(), panelKey),
                execution.runId(),
                panelKey,
                purpose,
                inputHash,
                DebateStatus.RUNNING,
                configuredRounds,
                0,
                decisionNodeId,
                clock.instant(),
                null,
                0);
        try {
            executionStore.startDebate(proposed);
        } catch (DecisionPanelSeedConflictException exception) {
            throw new SdbScaExecutionException(
                    "DECISION_PANEL_SEED_CONFLICT",
                    "Decision panel session conflicts with a concurrent immutable seed",
                    false);
        }
        var persisted = executionStore.findDebate(execution.runId(), panelKey).orElse(proposed);
        return DecisionPanelSessionGuard.requireCompatible(
                persisted, panelKey, purpose, inputHash, decisionNodeId);
    }
}
