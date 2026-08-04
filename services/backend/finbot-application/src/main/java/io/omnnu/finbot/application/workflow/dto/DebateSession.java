package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.debate.DecisionPanelInputHash;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.Objects;

public record DebateSession(
        DebateId debateId,
        WorkflowRunId runId,
        DecisionPanelKey panelKey,
        DecisionPanelPurpose panelPurpose,
        DecisionPanelInputHash inputHash,
        DebateStatus status,
        int configuredRounds,
        int completedRounds,
        WorkflowNodeId decisionNodeId,
        Instant startedAt,
        Instant completedAt,
        long version) {
    public DebateSession {
        Objects.requireNonNull(debateId, "debateId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(panelKey, "panelKey");
        Objects.requireNonNull(panelPurpose, "panelPurpose");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decisionNodeId, "decisionNodeId");
        Objects.requireNonNull(startedAt, "startedAt");
        if (configuredRounds < 1 || completedRounds < 0 || completedRounds > configuredRounds) {
            throw new IllegalArgumentException("Invalid decision panel round progress");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Decision panel version cannot be negative");
        }
    }
}
