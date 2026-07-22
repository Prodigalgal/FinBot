package io.omnnu.finbot.domain.debate;

import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record DebateTask(
        DebateTaskId taskId,
        DebatePhaseId phaseId,
        WorkflowNodeId actorNodeId,
        LogicalRoleKey logicalRoleKey,
        String targetCandidateId,
        DebateTaskVariant variant,
        String inputHash,
        DebateTaskStatus status,
        int attempt,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant completedAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public DebateTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(phaseId, "phaseId");
        Objects.requireNonNull(actorNodeId, "actorNodeId");
        Objects.requireNonNull(logicalRoleKey, "logicalRoleKey");
        targetCandidateId = optional(targetCandidateId);
        Objects.requireNonNull(variant, "variant");
        inputHash = Objects.requireNonNull(inputHash, "inputHash").strip();
        Objects.requireNonNull(status, "status");
        leaseOwner = optional(leaseOwner);
        Objects.requireNonNull(createdAt, "createdAt");
        if (!SHA_256.matcher(inputHash).matches()) {
            throw new IllegalArgumentException("inputHash must be a lowercase SHA-256 value");
        }
        if (attempt < 0 || attempt > 5) {
            throw new IllegalArgumentException("attempt must be between 0 and 5");
        }
        if (completedAt != null && status != DebateTaskStatus.COMPLETED
                && status != DebateTaskStatus.FAILED
                && status != DebateTaskStatus.TIMED_OUT
                && status != DebateTaskStatus.CANCELLED) {
            throw new IllegalArgumentException("Only terminal debate tasks can have completedAt");
        }
        if (status == DebateTaskStatus.CLAIMED && (leaseOwner == null || leaseExpiresAt == null)) {
            throw new IllegalArgumentException("A claimed debate task requires a lease owner and expiry");
        }
        if (status != DebateTaskStatus.CLAIMED && (leaseOwner != null || leaseExpiresAt != null)) {
            throw new IllegalArgumentException("Only a claimed debate task can carry lease state");
        }
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > 80) {
            throw new IllegalArgumentException("targetCandidateId is too long");
        }
        return normalized;
    }
}
