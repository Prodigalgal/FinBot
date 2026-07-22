package io.omnnu.finbot.domain.consensus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;

/**
 * Immutable audit snapshot for one ballot orientation of a Schulze aggregation.
 *
 * <p>{@link #candidateOrder()} is a stable representation order (sorted by candidate id) used to
 * index both matrices. It is never a social-choice tie-break.
 *
 * <p>Matrices are always non-null, deeply immutable, and sized exactly to {@code candidateOrder}.
 * Empty ballots yield a zero-sized candidate order with matching {@code 0×0} matrices.
 */
public record SchulzeOrientationSnapshot(
        List<AnonymousCandidateId> candidateOrder,
        ImmutableSquareIntMatrix pairwiseMatrix,
        ImmutableSquareIntMatrix strongestPathMatrix,
        ConsensusStatus status,
        AnonymousCandidateId uniqueStrictWinner,
        List<AnonymousCandidateId> undefeatedCandidates,
        int contributingRoleCount) {

    public SchulzeOrientationSnapshot {
        Objects.requireNonNull(candidateOrder, "candidateOrder");
        Objects.requireNonNull(pairwiseMatrix, "pairwiseMatrix");
        Objects.requireNonNull(strongestPathMatrix, "strongestPathMatrix");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(undefeatedCandidates, "undefeatedCandidates");
        candidateOrder = List.copyOf(candidateOrder);
        undefeatedCandidates = List.copyOf(undefeatedCandidates);
        if (candidateOrder.stream().anyMatch(Objects::isNull)
                || new HashSet<>(candidateOrder).size() != candidateOrder.size()) {
            throw new IllegalArgumentException("candidateOrder must contain unique non-null candidates");
        }
        if (undefeatedCandidates.stream().anyMatch(Objects::isNull)
                || new HashSet<>(undefeatedCandidates).size() != undefeatedCandidates.size()
                || !candidateOrder.containsAll(undefeatedCandidates)) {
            throw new IllegalArgumentException(
                    "undefeatedCandidates must be a unique subset of candidateOrder");
        }
        var expectedSize = candidateOrder.size();
        if (pairwiseMatrix.size() != expectedSize) {
            throw new IllegalArgumentException(
                    "pairwiseMatrix size "
                            + pairwiseMatrix.size()
                            + " must equal candidateOrder size "
                            + expectedSize);
        }
        if (strongestPathMatrix.size() != expectedSize) {
            throw new IllegalArgumentException(
                    "strongestPathMatrix size "
                            + strongestPathMatrix.size()
                            + " must equal candidateOrder size "
                            + expectedSize);
        }
        if (contributingRoleCount < 0) {
            throw new IllegalArgumentException("contributingRoleCount must not be negative");
        }
        if (status == ConsensusStatus.SELECTED) {
            Objects.requireNonNull(uniqueStrictWinner, "uniqueStrictWinner");
            if (!candidateOrder.contains(uniqueStrictWinner)
                    || !undefeatedCandidates.contains(uniqueStrictWinner)) {
                throw new IllegalArgumentException(
                        "uniqueStrictWinner must be an undefeated candidate in candidateOrder");
            }
        } else if (uniqueStrictWinner != null) {
            throw new IllegalArgumentException(
                    "uniqueStrictWinner is only allowed when orientation status is SELECTED");
        }
    }

    public Optional<AnonymousCandidateId> uniqueStrictWinnerOptional() {
        return Optional.ofNullable(uniqueStrictWinner);
    }

    /**
     * Snapshot for an orientation with no valid ballots: empty or zero-filled matrices, no winner.
     */
    public static SchulzeOrientationSnapshot noValidBallots(
            List<AnonymousCandidateId> candidateOrder, int contributingRoleCount) {
        var order = List.copyOf(Objects.requireNonNull(candidateOrder, "candidateOrder"));
        var zeros = ImmutableSquareIntMatrix.zeros(order.size());
        return new SchulzeOrientationSnapshot(
                order,
                zeros,
                zeros,
                ConsensusStatus.NO_VALID_BALLOTS,
                null,
                List.of(),
                contributingRoleCount);
    }

    public static SchulzeOrientationSnapshot of(
            List<AnonymousCandidateId> candidateOrder,
            ImmutableSquareIntMatrix pairwiseMatrix,
            ImmutableSquareIntMatrix strongestPathMatrix,
            ConsensusStatus status,
            AnonymousCandidateId uniqueStrictWinner,
            List<AnonymousCandidateId> undefeatedCandidates,
            int contributingRoleCount) {
        return new SchulzeOrientationSnapshot(
                candidateOrder,
                pairwiseMatrix,
                strongestPathMatrix,
                status,
                uniqueStrictWinner,
                undefeatedCandidates,
                contributingRoleCount);
    }
}
