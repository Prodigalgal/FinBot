package io.omnnu.finbot.domain.consensus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic result of role-normalized winning-votes Schulze aggregation.
 */
public record SchulzeOutcome(
        ConsensusStatus status,
        AnonymousCandidateId selectedCandidate,
        List<AnonymousCandidateId> undefeatedCandidates,
        int contributingRoleCount) {

    public SchulzeOutcome {
        Objects.requireNonNull(status, "status");
        undefeatedCandidates =
                List.copyOf(Objects.requireNonNull(undefeatedCandidates, "undefeatedCandidates"));
        if (contributingRoleCount < 0) {
            throw new IllegalArgumentException("contributingRoleCount must not be negative");
        }
        if (status == ConsensusStatus.SELECTED) {
            Objects.requireNonNull(selectedCandidate, "selectedCandidate");
        } else if (selectedCandidate != null) {
            throw new IllegalArgumentException(
                    "selectedCandidate is only allowed when status is SELECTED");
        }
    }

    public Optional<AnonymousCandidateId> selected() {
        return Optional.ofNullable(selectedCandidate);
    }

    public static SchulzeOutcome selected(
            AnonymousCandidateId winner,
            List<AnonymousCandidateId> undefeatedCandidates,
            int contributingRoleCount) {
        return new SchulzeOutcome(
                ConsensusStatus.SELECTED, winner, undefeatedCandidates, contributingRoleCount);
    }

    public static SchulzeOutcome unsuccessful(
            ConsensusStatus status,
            List<AnonymousCandidateId> undefeatedCandidates,
            int contributingRoleCount) {
        if (status == ConsensusStatus.SELECTED) {
            throw new IllegalArgumentException("use selected(...) for SELECTED outcomes");
        }
        return new SchulzeOutcome(status, null, undefeatedCandidates, contributingRoleCount);
    }
}
