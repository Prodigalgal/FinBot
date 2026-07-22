package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.workflow.DebateId;
import java.time.Instant;
import java.util.Objects;

public record DebatePhase(
        DebatePhaseId phaseId,
        DebateId debateId,
        DebateProtocol protocol,
        int generation,
        DebatePhaseType phaseType,
        DebatePhaseStatus status,
        int requiredTasks,
        int completedTasks,
        Instant deadline,
        Instant openedAt,
        Instant revealedAt,
        Instant completedAt,
        long version) {
    public DebatePhase {
        Objects.requireNonNull(phaseId, "phaseId");
        Objects.requireNonNull(debateId, "debateId");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(phaseType, "phaseType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(deadline, "deadline");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (requiredTasks < 1 || completedTasks < 0 || completedTasks > requiredTasks) {
            throw new IllegalArgumentException("Invalid debate phase task counters");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (revealedAt != null && openedAt == null) {
            throw new IllegalArgumentException("A revealed phase must have openedAt");
        }
        if (completedAt != null && revealedAt == null) {
            throw new IllegalArgumentException("A completed phase must have revealedAt");
        }
    }

    public boolean barrierSatisfied() {
        return completedTasks == requiredTasks;
    }
}
