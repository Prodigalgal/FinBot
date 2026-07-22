package io.omnnu.finbot.domain.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SchulzeConsensusEngineTest {

    private final SchulzeConsensusEngine engine = new SchulzeConsensusEngine();

    private static final AnonymousCandidateId A = candidate("cand-a");
    private static final AnonymousCandidateId B = candidate("cand-b");
    private static final AnonymousCandidateId C = candidate("cand-c");
    private static final AnonymousCandidateId D = candidate("cand-d");

    private static final LogicalRoleKey ANALYST = role("analyst");
    private static final LogicalRoleKey RISK = role("risk");
    private static final LogicalRoleKey TRADER = role("trader");

    @Test
    void selectsCondorcetWinnerWhenForwardAndReversedAgree() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, A, C, B),
                ranking(TRADER, BallotOrientation.FORWARD, A, B, C));
        var reversed = List.of(
                ranking(ANALYST, BallotOrientation.REVERSED, A, C, B),
                ranking(RISK, BallotOrientation.REVERSED, A, B, C),
                ranking(TRADER, BallotOrientation.REVERSED, A, C, B));

        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(ConsensusStatus.SELECTED, outcome.status());
        assertEquals(A, outcome.selectedCandidate());
        assertEquals(3, outcome.contributingRoleCount());
    }

    @Test
    void sameRoleThreeSeatsDoNotAmplifyWeightBeyondOne() {
        // Three analyst seats prefer A; risk and trader prefer B. Without role normalization
        // analyst would outweigh the others; with weight-1 roles the winner must be B.
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, B, C, A),
                ranking(TRADER, BallotOrientation.FORWARD, B, C, A));
        var reversed = samePreferencesReversedOrientation(forward);

        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(ConsensusStatus.SELECTED, outcome.status());
        assertEquals(B, outcome.selectedCandidate());
        assertEquals(3, outcome.contributingRoleCount());
    }

    @Test
    void resolvesCondorcetProfileWhereSchulzePathConfirmsUniqueWinner() {
        // Five roles with a Condorcet cycle in a subset of ballots, but A is the unique
        // Condorcet/Schulze winner after path strengths:
        // r1 A>B>C, r2 B>C>A, r3 C>A>B, r4 A>C>B, r5 A>B>C
        var r1 = role("role-1");
        var r2 = role("role-2");
        var r3 = role("role-3");
        var r4 = role("role-4");
        var r5 = role("role-5");
        var forward = List.of(
                ranking(r1, BallotOrientation.FORWARD, A, B, C),
                ranking(r2, BallotOrientation.FORWARD, B, C, A),
                ranking(r3, BallotOrientation.FORWARD, C, A, B),
                ranking(r4, BallotOrientation.FORWARD, A, C, B),
                ranking(r5, BallotOrientation.FORWARD, A, B, C));
        var reversed = samePreferencesReversedOrientation(forward);

        var outcome = engine.resolve(forward, reversed, 3);

        assertEquals(ConsensusStatus.SELECTED, outcome.status());
        assertEquals(A, outcome.selectedCandidate());
    }

    @Test
    void schulzePathBreaksRockPaperScissorsWithUnequalMargins() {
        // Cycle A→B→C→A with unequal role margins; strongest path selects A.
        // 5× A>B>C, 4× B>C>A, 2× C>A>B
        // pairwise: A≻B 7-4, B≻C 9-2, C≻A 6-5; path A→C = min(7,9)=7 > 6
        var forward = new ArrayList<AnonymousPreferenceBallot>();
        for (var i = 0; i < 5; i++) {
            forward.add(ranking(role("pro-a-" + i), BallotOrientation.FORWARD, A, B, C));
        }
        for (var i = 0; i < 4; i++) {
            forward.add(ranking(role("pro-b-" + i), BallotOrientation.FORWARD, B, C, A));
        }
        for (var i = 0; i < 2; i++) {
            forward.add(ranking(role("pro-c-" + i), BallotOrientation.FORWARD, C, A, B));
        }
        var reversed = samePreferencesReversedOrientation(forward);

        var outcome = engine.resolve(forward, reversed, 5);

        assertEquals(ConsensusStatus.SELECTED, outcome.status());
        assertEquals(A, outcome.selectedCandidate());
    }

    @Test
    void returnsTiedWhenAllRolesSplitEvenlyBetweenTwoCandidates() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B),
                ranking(RISK, BallotOrientation.FORWARD, B, A));
        var reversed = List.of(
                ranking(ANALYST, BallotOrientation.REVERSED, A, B),
                ranking(RISK, BallotOrientation.REVERSED, B, A));

        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(ConsensusStatus.TIED, outcome.status());
        assertTrue(outcome.selected().isEmpty());
        assertEquals(Set.of(A, B), Set.copyOf(outcome.undefeatedCandidates()));
    }

    @Test
    void returnsLowQuorumWhenDistinctRolesBelowMinimum() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(ANALYST, BallotOrientation.FORWARD, A, C, B));
        var reversed = samePreferencesReversedOrientation(forward);

        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(ConsensusStatus.LOW_QUORUM, outcome.status());
        assertEquals(1, outcome.contributingRoleCount());
        assertTrue(outcome.selected().isEmpty());
    }

    @Test
    void returnsOrderSensitiveWhenForwardAndReversedUniqueWinnersDiffer() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, A, C, B),
                ranking(TRADER, BallotOrientation.FORWARD, A, B, C));
        var reversed = List.of(
                ranking(ANALYST, BallotOrientation.REVERSED, B, A, C),
                ranking(RISK, BallotOrientation.REVERSED, B, C, A),
                ranking(TRADER, BallotOrientation.REVERSED, B, A, C));

        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(ConsensusStatus.ORDER_SENSITIVE, outcome.status());
        assertTrue(outcome.selected().isEmpty());
    }

    @Test
    void returnsNoValidBallotsWhenBothOrientationsEmpty() {
        var outcome = engine.resolve(List.of(), List.of(), 1);
        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, outcome.status());
        assertEquals(0, outcome.contributingRoleCount());
    }

    @Test
    void returnsNoValidBallotsWhenOneOrientationMissing() {
        var forward = List.of(ranking(ANALYST, BallotOrientation.FORWARD, A, B));
        var outcome = engine.resolve(forward, List.of(), 1);
        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, outcome.status());
    }

    @Test
    void resultInvariantUnderCandidateAndBallotInputPermutation() {
        var baseForward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C, D),
                ranking(RISK, BallotOrientation.FORWARD, A, C, B, D),
                ranking(TRADER, BallotOrientation.FORWARD, A, D, B, C));
        var baseReversed = samePreferencesReversedOrientation(baseForward);
        var baseline = engine.resolve(baseForward, baseReversed, 2);

        var reorderedForward = List.of(
                ranking(TRADER, BallotOrientation.FORWARD, A, D, B, C),
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C, D),
                ranking(RISK, BallotOrientation.FORWARD, A, C, B, D));
        var reorderedReversed = List.of(
                ranking(RISK, BallotOrientation.REVERSED, A, C, B, D),
                ranking(TRADER, BallotOrientation.REVERSED, A, D, B, C),
                ranking(ANALYST, BallotOrientation.REVERSED, A, B, C, D));

        var explicitTierForward = List.of(
                rankingTiers(ANALYST, BallotOrientation.FORWARD, List.of(A), List.of(B), List.of(C), List.of(D)),
                rankingTiers(RISK, BallotOrientation.FORWARD, List.of(A), List.of(C), List.of(B), List.of(D)),
                rankingTiers(TRADER, BallotOrientation.FORWARD, List.of(A), List.of(D), List.of(B), List.of(C)));
        var explicitTierReversed = samePreferencesReversedOrientation(explicitTierForward);

        var permutedBallots = engine.resolve(reorderedForward, reorderedReversed, 2);
        var permutedPresentation = engine.resolve(explicitTierForward, explicitTierReversed, 2);

        assertEquals(ConsensusStatus.SELECTED, baseline.status());
        assertEquals(baseline.status(), permutedBallots.status());
        assertEquals(baseline.selectedCandidate(), permutedBallots.selectedCandidate());
        assertEquals(baseline.status(), permutedPresentation.status());
        assertEquals(baseline.selectedCandidate(), permutedPresentation.selectedCandidate());

        var tieForward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B),
                ranking(RISK, BallotOrientation.FORWARD, B, A));
        var tieOutcome = engine.resolve(tieForward, samePreferencesReversedOrientation(tieForward), 2);
        var tieSwapped = engine.resolve(
                List.of(
                        ranking(ANALYST, BallotOrientation.FORWARD, B, A),
                        ranking(RISK, BallotOrientation.FORWARD, A, B)),
                List.of(
                        ranking(ANALYST, BallotOrientation.REVERSED, B, A),
                        ranking(RISK, BallotOrientation.REVERSED, A, B)),
                2);
        assertEquals(ConsensusStatus.TIED, tieOutcome.status());
        assertEquals(ConsensusStatus.TIED, tieSwapped.status());
        assertEquals(
                Set.copyOf(tieOutcome.undefeatedCandidates()),
                Set.copyOf(tieSwapped.undefeatedCandidates()));
    }

    @Test
    void rejectsMalformedOrIncompleteBallots() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AnonymousPreferenceBallot(
                        ANALYST,
                        BallotOrientation.FORWARD,
                        List.of(Set.of(A), Set.of(A, B))));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AnonymousPreferenceBallot(
                        ANALYST, BallotOrientation.FORWARD, List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new AnonymousPreferenceBallot(
                        ANALYST, BallotOrientation.FORWARD, List.of(Set.of())));

        var incompleteForward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, A, B));
        var completeReversed = List.of(
                ranking(ANALYST, BallotOrientation.REVERSED, A, B, C),
                ranking(RISK, BallotOrientation.REVERSED, A, C, B));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.resolve(incompleteForward, completeReversed, 1));

        var wrongOrientation = List.of(ranking(ANALYST, BallotOrientation.REVERSED, A, B));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.resolve(
                        wrongOrientation,
                        List.of(ranking(ANALYST, BallotOrientation.REVERSED, A, B)),
                        1));

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.resolve(
                        List.of(ranking(ANALYST, BallotOrientation.FORWARD, A, B)),
                        List.of(ranking(ANALYST, BallotOrientation.REVERSED, A, B)),
                        0));
    }

    @Test
    void rejectsDifferentLogicalRoleSeatCompositionAcrossOrientations() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B),
                ranking(RISK, BallotOrientation.FORWARD, B, A));
        var reversed = List.of(
                ranking(ANALYST, BallotOrientation.REVERSED, A, B),
                ranking(TRADER, BallotOrientation.REVERSED, B, A));

        assertThrows(
                IllegalArgumentException.class,
                () -> engine.resolve(forward, reversed, 2));
    }

    @Test
    void supportsTotalPreorderTiersWithIntraTierTies() {
        var tiered = List.of(Set.of(A, B), Set.of(C));
        var forward = List.of(
                new AnonymousPreferenceBallot(ANALYST, BallotOrientation.FORWARD, tiered),
                new AnonymousPreferenceBallot(RISK, BallotOrientation.FORWARD, tiered),
                new AnonymousPreferenceBallot(TRADER, BallotOrientation.FORWARD, tiered));
        var reversed = List.of(
                new AnonymousPreferenceBallot(ANALYST, BallotOrientation.REVERSED, tiered),
                new AnonymousPreferenceBallot(RISK, BallotOrientation.REVERSED, tiered),
                new AnonymousPreferenceBallot(TRADER, BallotOrientation.REVERSED, tiered));

        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(ConsensusStatus.TIED, outcome.status());
        assertEquals(Set.of(A, B), Set.copyOf(outcome.undefeatedCandidates()));
    }

    @Test
    void resolveDelegatesToDetailedAndPreservesOutcome() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, A, C, B),
                ranking(TRADER, BallotOrientation.FORWARD, A, B, C));
        var reversed = samePreferencesReversedOrientation(forward);

        var detailed = engine.resolveDetailed(forward, reversed, 2);
        var outcome = engine.resolve(forward, reversed, 2);

        assertEquals(outcome, detailed.outcome());
        assertEquals(ConsensusStatus.SELECTED, detailed.outcome().status());
        assertEquals(A, detailed.forward().uniqueStrictWinner());
        assertEquals(A, detailed.reversed().uniqueStrictWinner());
    }

    @Test
    void detailedAuditSnapshotsForUniqueWinnerContainStableOrderAndMatrices() {
        // Three distinct roles all prefer A ≻ B ≻ C (role-normalized weight 1 each).
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, A, B, C),
                ranking(TRADER, BallotOrientation.FORWARD, A, B, C));
        var reversed = samePreferencesReversedOrientation(forward);

        var detailed = engine.resolveDetailed(forward, reversed, 2);
        assertEquals(ConsensusStatus.SELECTED, detailed.outcome().status());

        for (var snapshot : List.of(detailed.forward(), detailed.reversed())) {
            assertAuditableSnapshot(snapshot, 3, 3);
            assertEquals(List.of(A, B, C), snapshot.candidateOrder());
            assertEquals(ConsensusStatus.SELECTED, snapshot.status());
            assertEquals(A, snapshot.uniqueStrictWinner());
            assertEquals(List.of(A), snapshot.undefeatedCandidates());
            assertEquals(3, snapshot.contributingRoleCount());

            // candidateOrder sorted: A=0, B=1, C=2
            // pairwise: A≻B, A≻C, B≻C each with margin 3
            assertEquals(3, snapshot.pairwiseMatrix().get(0, 1));
            assertEquals(3, snapshot.pairwiseMatrix().get(0, 2));
            assertEquals(3, snapshot.pairwiseMatrix().get(1, 2));
            assertEquals(0, snapshot.pairwiseMatrix().get(1, 0));
            assertEquals(0, snapshot.pairwiseMatrix().get(2, 0));
            assertEquals(0, snapshot.pairwiseMatrix().get(2, 1));

            assertEquals(3, snapshot.strongestPathMatrix().get(0, 1));
            assertEquals(3, snapshot.strongestPathMatrix().get(0, 2));
            assertEquals(3, snapshot.strongestPathMatrix().get(1, 2));
        }
    }

    @Test
    void detailedAuditSnapshotsForTiedOrientationKeepNonNullMatrices() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B),
                ranking(RISK, BallotOrientation.FORWARD, B, A));
        var reversed = samePreferencesReversedOrientation(forward);

        var detailed = engine.resolveDetailed(forward, reversed, 2);

        assertEquals(ConsensusStatus.TIED, detailed.outcome().status());
        for (var snapshot : List.of(detailed.forward(), detailed.reversed())) {
            assertAuditableSnapshot(snapshot, 2, 2);
            assertEquals(ConsensusStatus.TIED, snapshot.status());
            assertNull(snapshot.uniqueStrictWinner());
            assertEquals(Set.of(A, B), Set.copyOf(snapshot.undefeatedCandidates()));
            // Each role contributes weight 1 on opposite sides → equal pairwise margins
            assertEquals(1, snapshot.pairwiseMatrix().get(0, 1));
            assertEquals(1, snapshot.pairwiseMatrix().get(1, 0));
            // Equal pairwise margins yield zero strongest-path entries
            assertEquals(0, snapshot.strongestPathMatrix().get(0, 1));
            assertEquals(0, snapshot.strongestPathMatrix().get(1, 0));
        }
    }

    @Test
    void detailedAuditSnapshotsForLowQuorumStillComputeMatrices() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(ANALYST, BallotOrientation.FORWARD, A, C, B));
        var reversed = samePreferencesReversedOrientation(forward);

        var detailed = engine.resolveDetailed(forward, reversed, 2);

        assertEquals(ConsensusStatus.LOW_QUORUM, detailed.outcome().status());
        for (var snapshot : List.of(detailed.forward(), detailed.reversed())) {
            assertAuditableSnapshot(snapshot, 3, 1);
            assertEquals(ConsensusStatus.LOW_QUORUM, snapshot.status());
            assertNull(snapshot.uniqueStrictWinner());
            assertEquals(List.of(A, B, C), snapshot.candidateOrder());
            assertEquals(Set.of(A, B, C), Set.copyOf(snapshot.undefeatedCandidates()));
            // Single role prefers A over B and C → pairwise weight 1 on A→B and A→C
            assertEquals(1, snapshot.pairwiseMatrix().get(0, 1));
            assertEquals(1, snapshot.pairwiseMatrix().get(0, 2));
        }
    }

    @Test
    void detailedAuditSnapshotsForOrderSensitiveExposeDivergentWinners() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B, C),
                ranking(RISK, BallotOrientation.FORWARD, A, C, B),
                ranking(TRADER, BallotOrientation.FORWARD, A, B, C));
        var reversed = List.of(
                ranking(ANALYST, BallotOrientation.REVERSED, B, A, C),
                ranking(RISK, BallotOrientation.REVERSED, B, C, A),
                ranking(TRADER, BallotOrientation.REVERSED, B, A, C));

        var detailed = engine.resolveDetailed(forward, reversed, 2);

        assertEquals(ConsensusStatus.ORDER_SENSITIVE, detailed.outcome().status());
        assertAuditableSnapshot(detailed.forward(), 3, 3);
        assertAuditableSnapshot(detailed.reversed(), 3, 3);
        assertEquals(ConsensusStatus.SELECTED, detailed.forward().status());
        assertEquals(ConsensusStatus.SELECTED, detailed.reversed().status());
        assertEquals(A, detailed.forward().uniqueStrictWinner());
        assertEquals(B, detailed.reversed().uniqueStrictWinner());
        assertNotEquals(
                detailed.forward().uniqueStrictWinner(), detailed.reversed().uniqueStrictWinner());
    }

    @Test
    void detailedAuditSnapshotsForEmptyBallotsRemainNonNullAndKeepKnownCandidateUniverse() {
        var bothEmpty = engine.resolveDetailed(List.of(), List.of(), 1);

        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, bothEmpty.outcome().status());
        assertAuditableSnapshot(bothEmpty.forward(), 0, 0);
        assertAuditableSnapshot(bothEmpty.reversed(), 0, 0);
        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, bothEmpty.forward().status());
        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, bothEmpty.reversed().status());

        var forwardOnly = List.of(ranking(ANALYST, BallotOrientation.FORWARD, A, B));
        var oneEmpty = engine.resolveDetailed(forwardOnly, List.of(), 1);

        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, oneEmpty.outcome().status());
        assertAuditableSnapshot(oneEmpty.forward(), 2, 1);
        assertAuditableSnapshot(oneEmpty.reversed(), 2, 0);
        assertEquals(ConsensusStatus.NO_VALID_BALLOTS, oneEmpty.reversed().status());
        assertNotNull(oneEmpty.forward().pairwiseMatrix());
        assertNotNull(oneEmpty.forward().strongestPathMatrix());
    }

    @Test
    void detailedAuditMatricesAreDeeplyImmutable() {
        var forward = List.of(
                ranking(ANALYST, BallotOrientation.FORWARD, A, B),
                ranking(RISK, BallotOrientation.FORWARD, A, B));
        var reversed = samePreferencesReversedOrientation(forward);

        var detailed = engine.resolveDetailed(forward, reversed, 2);
        var matrix = detailed.forward().pairwiseMatrix();
        var copy = matrix.toArray();
        copy[0][1] = 999;
        assertEquals(2, matrix.get(0, 1));
        assertEquals(2, matrix.toArray()[0][1]);

        assertThrows(
                IllegalArgumentException.class,
                () -> ImmutableSquareIntMatrix.copyOf(new int[][] {{1, 2}, {3}}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SchulzeOrientationSnapshot(
                        List.of(A, B),
                        ImmutableSquareIntMatrix.zeros(3),
                        ImmutableSquareIntMatrix.zeros(2),
                        ConsensusStatus.TIED,
                        null,
                        List.of(A, B),
                        1));
    }

    @Test
    void schulzePathMarginsAreCapturedInAuditSnapshot() {
        // Same profile as schulzePathBreaksRockPaperScissorsWithUnequalMargins
        var forward = new ArrayList<AnonymousPreferenceBallot>();
        for (var i = 0; i < 5; i++) {
            forward.add(ranking(role("pro-a-" + i), BallotOrientation.FORWARD, A, B, C));
        }
        for (var i = 0; i < 4; i++) {
            forward.add(ranking(role("pro-b-" + i), BallotOrientation.FORWARD, B, C, A));
        }
        for (var i = 0; i < 2; i++) {
            forward.add(ranking(role("pro-c-" + i), BallotOrientation.FORWARD, C, A, B));
        }
        var reversed = samePreferencesReversedOrientation(forward);

        var snapshot = engine.resolveDetailed(forward, reversed, 5).forward();
        // order A=0, B=1, C=2
        // role-normalized pairwise stores each role's preferred direction (weight 1):
        // A≻B 7-4, B≻C 9-2, C≻A 6-5
        assertEquals(7, snapshot.pairwiseMatrix().get(0, 1));
        assertEquals(4, snapshot.pairwiseMatrix().get(1, 0));
        assertEquals(9, snapshot.pairwiseMatrix().get(1, 2));
        assertEquals(2, snapshot.pairwiseMatrix().get(2, 1));
        assertEquals(5, snapshot.pairwiseMatrix().get(0, 2));
        assertEquals(6, snapshot.pairwiseMatrix().get(2, 0));
        // path A→C = min(7,9)=7 > direct C→A path strength 6
        assertEquals(7, snapshot.strongestPathMatrix().get(0, 2));
        assertEquals(6, snapshot.strongestPathMatrix().get(2, 0));
        assertEquals(A, snapshot.uniqueStrictWinner());
    }

    private static void assertAuditableSnapshot(
            SchulzeOrientationSnapshot snapshot, int expectedCandidateCount, int expectedRoles) {
        assertNotNull(snapshot);
        assertNotNull(snapshot.candidateOrder());
        assertNotNull(snapshot.pairwiseMatrix());
        assertNotNull(snapshot.strongestPathMatrix());
        assertNotNull(snapshot.status());
        assertNotNull(snapshot.undefeatedCandidates());
        assertEquals(expectedCandidateCount, snapshot.candidateOrder().size());
        assertEquals(expectedCandidateCount, snapshot.pairwiseMatrix().size());
        assertEquals(expectedCandidateCount, snapshot.strongestPathMatrix().size());
        assertEquals(expectedRoles, snapshot.contributingRoleCount());
    }

    /**
     * Order-invariant seat: reversed-orientation ballot keeps the same true preference tiers.
     * (Literal preference inversion would model order-sensitivity, not agreement.)
     */
    private static List<AnonymousPreferenceBallot> samePreferencesReversedOrientation(
            List<AnonymousPreferenceBallot> forwardBallots) {
        var reversed = new ArrayList<AnonymousPreferenceBallot>(forwardBallots.size());
        for (var ballot : forwardBallots) {
            reversed.add(new AnonymousPreferenceBallot(
                    ballot.logicalRoleKey(),
                    BallotOrientation.REVERSED,
                    ballot.preferenceTiers()));
        }
        return reversed;
    }

    private static AnonymousPreferenceBallot ranking(
            LogicalRoleKey roleKey, BallotOrientation orientation, AnonymousCandidateId... order) {
        var tiers = Arrays.stream(order)
                .<Set<AnonymousCandidateId>>map(Set::of)
                .toList();
        return new AnonymousPreferenceBallot(roleKey, orientation, tiers);
    }

    @SafeVarargs
    private static AnonymousPreferenceBallot rankingTiers(
            LogicalRoleKey roleKey,
            BallotOrientation orientation,
            List<AnonymousCandidateId>... tiers) {
        var preferenceTiers = new ArrayList<Set<AnonymousCandidateId>>(tiers.length);
        for (var tier : tiers) {
            preferenceTiers.add(new LinkedHashSet<>(tier));
        }
        return new AnonymousPreferenceBallot(roleKey, orientation, preferenceTiers);
    }

    private static AnonymousCandidateId candidate(String value) {
        return new AnonymousCandidateId(value);
    }

    private static LogicalRoleKey role(String value) {
        return new LogicalRoleKey(value);
    }
}
