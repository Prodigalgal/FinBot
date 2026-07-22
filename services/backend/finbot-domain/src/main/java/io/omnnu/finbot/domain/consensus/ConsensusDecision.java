package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.workflow.DebateId;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ConsensusDecision(
        ConsensusDecisionId decisionId,
        DebateId debateId,
        SchulzeOutcome outcome,
        DebateCandidateId winnerCandidateId,
        String pairwiseMatrixJson,
        String strongestPathsJson,
        String rankingJson,
        String forecastJson,
        String explanation,
        String decisionHash,
        Instant decidedAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ConsensusDecision {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(debateId, "debateId");
        Objects.requireNonNull(outcome, "outcome");
        pairwiseMatrixJson = required(pairwiseMatrixJson, "pairwiseMatrixJson");
        strongestPathsJson = required(strongestPathsJson, "strongestPathsJson");
        rankingJson = required(rankingJson, "rankingJson");
        explanation = explanation == null ? null : explanation.strip();
        decisionHash = Objects.requireNonNull(decisionHash, "decisionHash").strip();
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (outcome.status() == ConsensusStatus.SELECTED && winnerCandidateId == null) {
            throw new IllegalArgumentException("A selected consensus decision requires winnerCandidateId");
        }
        if (outcome.status() != ConsensusStatus.SELECTED && winnerCandidateId != null) {
            throw new IllegalArgumentException("Only a selected decision can reference a winner");
        }
        if (!SHA_256.matcher(decisionHash).matches()) {
            throw new IllegalArgumentException("decisionHash must be a lowercase SHA-256 value");
        }
    }

    private static String required(String value, String fieldName) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
