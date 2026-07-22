package io.omnnu.finbot.domain.consensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic role-normalized winning-votes Schulze engine for SDB-SCA social choice.
 *
 * <p>Algorithm boundary:
 *
 * <ol>
 *   <li>Validate complete total-preorder ballots and a shared candidate universe.
 *   <li>Within each {@link LogicalRoleKey}, seat majority decides every candidate pair; seat ties
 *       abstain. Each role contributes weight at most 1 per ordered pair.
 *   <li>Across roles, build the winning-votes pairwise matrix and compute Schulze strongest paths.
 *   <li>A unique strict winner exists only when one candidate strictly beats every other on path
 *       strength. Candidate ids and input order never break ties.
 *   <li>Final status is {@link ConsensusStatus#SELECTED} only when forward and reversed orientations
 *       share the same unique strict winner.
 * </ol>
 *
 * <p>Stable sorting of candidate ids is used solely for deterministic matrix traversal and
 * representation, never as a social-choice tie-break. Both orientations always emit non-null,
 * deeply immutable audit matrices (including empty-ballot and low-quorum paths).
 */
public final class SchulzeConsensusEngine {

    /**
     * Resolves the terminal social-choice outcome. Delegates to {@link #resolveDetailed} and
     * returns only {@link SchulzeDetailedResult#outcome()}.
     */
    public SchulzeOutcome resolve(
            List<AnonymousPreferenceBallot> forwardBallots,
            List<AnonymousPreferenceBallot> reversedBallots,
            int minQuorumRoles) {
        return resolveDetailed(forwardBallots, reversedBallots, minQuorumRoles).outcome();
    }

    /**
     * Resolves the terminal outcome together with immutable forward/reversed orientation audit
     * snapshots (candidate order, pairwise matrix, strongest-path matrix, status, winners).
     */
    public SchulzeDetailedResult resolveDetailed(
            List<AnonymousPreferenceBallot> forwardBallots,
            List<AnonymousPreferenceBallot> reversedBallots,
            int minQuorumRoles) {
        Objects.requireNonNull(forwardBallots, "forwardBallots");
        Objects.requireNonNull(reversedBallots, "reversedBallots");
        if (minQuorumRoles < 1) {
            throw new IllegalArgumentException("minQuorumRoles must be at least 1");
        }

        var forward = List.copyOf(forwardBallots);
        var reversed = List.copyOf(reversedBallots);
        requireOrientation(forward, BallotOrientation.FORWARD, "forwardBallots");
        requireOrientation(reversed, BallotOrientation.REVERSED, "reversedBallots");

        if (forward.isEmpty() && reversed.isEmpty()) {
            var empty = SchulzeOrientationSnapshot.noValidBallots(List.of(), 0);
            return new SchulzeDetailedResult(
                    SchulzeOutcome.unsuccessful(ConsensusStatus.NO_VALID_BALLOTS, List.of(), 0),
                    empty,
                    empty);
        }

        if (forward.isEmpty() || reversed.isEmpty()) {
            return resolveWhenOneOrientationEmpty(forward, reversed, minQuorumRoles);
        }

        var candidates = requireSharedCandidateUniverse(forward, reversed);
        requireSameRoleComposition(forward, reversed);
        var forwardSnapshot = evaluateOrientation(forward, candidates, minQuorumRoles);
        var reversedSnapshot = evaluateOrientation(reversed, candidates, minQuorumRoles);
        return new SchulzeDetailedResult(
                combine(forwardSnapshot, reversedSnapshot), forwardSnapshot, reversedSnapshot);
    }

    private static SchulzeDetailedResult resolveWhenOneOrientationEmpty(
            List<AnonymousPreferenceBallot> forward,
            List<AnonymousPreferenceBallot> reversed,
            int minQuorumRoles) {
        var forwardEmpty = forward.isEmpty();
        var nonEmpty = forwardEmpty ? reversed : forward;
        var roles = distinctRoleCount(nonEmpty);
        var candidates = stableCandidateOrderFromBallots(nonEmpty);
        var evaluated = evaluateOrientation(nonEmpty, candidates, minQuorumRoles);
        var emptySnapshot = SchulzeOrientationSnapshot.noValidBallots(candidates, 0);
        var outcome =
                SchulzeOutcome.unsuccessful(ConsensusStatus.NO_VALID_BALLOTS, List.of(), roles);
        if (forwardEmpty) {
            return new SchulzeDetailedResult(outcome, emptySnapshot, evaluated);
        }
        return new SchulzeDetailedResult(outcome, evaluated, emptySnapshot);
    }

    private static SchulzeOrientationSnapshot evaluateOrientation(
            List<AnonymousPreferenceBallot> ballots,
            List<AnonymousCandidateId> candidates,
            int minQuorumRoles) {
        requireCompleteBallots(ballots, candidates);

        var byRole = groupByRole(ballots);
        var contributingRoleCount = byRole.size();
        var pairwiseValues = roleNormalizedPairwise(byRole, candidates);
        var pathValues = strongestPaths(pairwiseValues);
        var pairwise = ImmutableSquareIntMatrix.copyOf(pairwiseValues);
        var pathStrength = ImmutableSquareIntMatrix.copyOf(pathValues);

        if (contributingRoleCount < minQuorumRoles) {
            return SchulzeOrientationSnapshot.of(
                    candidates,
                    pairwise,
                    pathStrength,
                    ConsensusStatus.LOW_QUORUM,
                    null,
                    candidates,
                    contributingRoleCount);
        }

        return classifySchulze(candidates, pairwise, pathStrength, contributingRoleCount);
    }

    private static SchulzeOutcome combine(
            SchulzeOrientationSnapshot forward, SchulzeOrientationSnapshot reversed) {
        var roleCount = Math.min(forward.contributingRoleCount(), reversed.contributingRoleCount());
        var undefeated =
                mergeUndefeated(forward.undefeatedCandidates(), reversed.undefeatedCandidates());

        if (forward.status() == ConsensusStatus.NO_VALID_BALLOTS
                || reversed.status() == ConsensusStatus.NO_VALID_BALLOTS) {
            return SchulzeOutcome.unsuccessful(ConsensusStatus.NO_VALID_BALLOTS, undefeated, roleCount);
        }
        if (forward.status() == ConsensusStatus.LOW_QUORUM
                || reversed.status() == ConsensusStatus.LOW_QUORUM) {
            return SchulzeOutcome.unsuccessful(ConsensusStatus.LOW_QUORUM, undefeated, roleCount);
        }
        if (forward.uniqueStrictWinner() != null && reversed.uniqueStrictWinner() != null) {
            if (forward.uniqueStrictWinner().equals(reversed.uniqueStrictWinner())) {
                return SchulzeOutcome.selected(
                        forward.uniqueStrictWinner(),
                        List.of(forward.uniqueStrictWinner()),
                        roleCount);
            }
            return SchulzeOutcome.unsuccessful(ConsensusStatus.ORDER_SENSITIVE, undefeated, roleCount);
        }
        if (forward.status() == ConsensusStatus.TIED || reversed.status() == ConsensusStatus.TIED) {
            return SchulzeOutcome.unsuccessful(ConsensusStatus.TIED, undefeated, roleCount);
        }
        return SchulzeOutcome.unsuccessful(ConsensusStatus.NO_STRICT_WINNER, undefeated, roleCount);
    }

    private static SchulzeOrientationSnapshot classifySchulze(
            List<AnonymousCandidateId> candidates,
            ImmutableSquareIntMatrix pairwise,
            ImmutableSquareIntMatrix pathStrength,
            int contributingRoleCount) {
        var n = candidates.size();
        var undefeated = new ArrayList<AnonymousCandidateId>();
        for (var i = 0; i < n; i++) {
            var beaten = false;
            for (var j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (pathStrength.get(j, i) > pathStrength.get(i, j)) {
                    beaten = true;
                    break;
                }
            }
            if (!beaten) {
                undefeated.add(candidates.get(i));
            }
        }

        if (undefeated.size() == 1) {
            var candidate = undefeated.getFirst();
            var index = candidates.indexOf(candidate);
            var strictlyBeatsAll = true;
            for (var j = 0; j < n; j++) {
                if (j == index) {
                    continue;
                }
                if (pathStrength.get(index, j) <= pathStrength.get(j, index)) {
                    strictlyBeatsAll = false;
                    break;
                }
            }
            if (strictlyBeatsAll) {
                return SchulzeOrientationSnapshot.of(
                        candidates,
                        pairwise,
                        pathStrength,
                        ConsensusStatus.SELECTED,
                        candidate,
                        List.copyOf(undefeated),
                        contributingRoleCount);
            }
            return SchulzeOrientationSnapshot.of(
                    candidates,
                    pairwise,
                    pathStrength,
                    ConsensusStatus.NO_STRICT_WINNER,
                    null,
                    List.copyOf(undefeated),
                    contributingRoleCount);
        }
        if (undefeated.size() > 1) {
            return SchulzeOrientationSnapshot.of(
                    candidates,
                    pairwise,
                    pathStrength,
                    ConsensusStatus.TIED,
                    null,
                    List.copyOf(undefeated),
                    contributingRoleCount);
        }
        return SchulzeOrientationSnapshot.of(
                candidates,
                pairwise,
                pathStrength,
                ConsensusStatus.NO_STRICT_WINNER,
                null,
                List.of(),
                contributingRoleCount);
    }

    private static int[][] roleNormalizedPairwise(
            Map<LogicalRoleKey, List<AnonymousPreferenceBallot>> byRole,
            List<AnonymousCandidateId> candidates) {
        var n = candidates.size();
        var pairwise = new int[n][n];

        // Role iteration order is only for accumulation determinism; weights are commutative.
        var roles = new ArrayList<>(byRole.keySet());
        roles.sort(Comparator.comparing(LogicalRoleKey::value));

        for (var role : roles) {
            var seats = byRole.get(role);
            for (var i = 0; i < n; i++) {
                for (var j = i + 1; j < n; j++) {
                    var left = candidates.get(i);
                    var right = candidates.get(j);
                    var preferLeft = 0;
                    var preferRight = 0;
                    for (var seat : seats) {
                        var comparison = seat.comparePreference(left, right);
                        if (comparison < 0) {
                            preferLeft++;
                        } else if (comparison > 0) {
                            preferRight++;
                        }
                    }
                    if (preferLeft > preferRight) {
                        pairwise[i][j] += 1;
                    } else if (preferRight > preferLeft) {
                        pairwise[j][i] += 1;
                    }
                    // equal seat counts → role abstains on this pair
                }
            }
        }
        return pairwise;
    }

    /**
     * Standard Schulze strongest-path computation on a winning-votes pairwise matrix.
     */
    private static int[][] strongestPaths(int[][] pairwise) {
        var n = pairwise.length;
        var path = new int[n][n];
        for (var i = 0; i < n; i++) {
            for (var j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (pairwise[i][j] > pairwise[j][i]) {
                    path[i][j] = pairwise[i][j];
                } else {
                    path[i][j] = 0;
                }
            }
        }

        for (var k = 0; k < n; k++) {
            for (var i = 0; i < n; i++) {
                if (i == k) {
                    continue;
                }
                for (var j = 0; j < n; j++) {
                    if (j == k || j == i) {
                        continue;
                    }
                    var through = Math.min(path[i][k], path[k][j]);
                    if (through > path[i][j]) {
                        path[i][j] = through;
                    }
                }
            }
        }
        return path;
    }

    private static Map<LogicalRoleKey, List<AnonymousPreferenceBallot>> groupByRole(
            List<AnonymousPreferenceBallot> ballots) {
        var byRole = new LinkedHashMap<LogicalRoleKey, List<AnonymousPreferenceBallot>>();
        for (var ballot : ballots) {
            byRole.computeIfAbsent(ballot.logicalRoleKey(), ignored -> new ArrayList<>()).add(ballot);
        }
        return byRole;
    }

    private static List<AnonymousCandidateId> requireSharedCandidateUniverse(
            List<AnonymousPreferenceBallot> forward, List<AnonymousPreferenceBallot> reversed) {
        Set<AnonymousCandidateId> universe = null;
        for (var ballot : concatenate(forward, reversed)) {
            var candidates = ballot.candidates();
            if (universe == null) {
                universe = candidates;
            } else if (!universe.equals(candidates)) {
                throw new IllegalArgumentException(
                        "all ballots must rank the same complete candidate set");
            }
        }
        if (universe == null || universe.isEmpty()) {
            throw new IllegalArgumentException("candidate universe must not be empty");
        }
        return stableOrder(universe);
    }

    private static List<AnonymousCandidateId> stableCandidateOrderFromBallots(
            List<AnonymousPreferenceBallot> ballots) {
        Set<AnonymousCandidateId> universe = null;
        for (var ballot : ballots) {
            var candidates = ballot.candidates();
            if (universe == null) {
                universe = candidates;
            } else if (!universe.equals(candidates)) {
                throw new IllegalArgumentException(
                        "all ballots must rank the same complete candidate set");
            }
        }
        if (universe == null || universe.isEmpty()) {
            throw new IllegalArgumentException("candidate universe must not be empty");
        }
        return stableOrder(universe);
    }

    /** Deterministic traversal/representation order only — not a social-choice tie-break. */
    private static List<AnonymousCandidateId> stableOrder(Set<AnonymousCandidateId> universe) {
        var ordered = new ArrayList<>(universe);
        ordered.sort(Comparator.comparing(AnonymousCandidateId::value));
        return List.copyOf(ordered);
    }

    private static void requireCompleteBallots(
            List<AnonymousPreferenceBallot> ballots, List<AnonymousCandidateId> candidates) {
        var expected = Set.copyOf(candidates);
        for (var ballot : ballots) {
            if (!ballot.candidates().equals(expected)) {
                throw new IllegalArgumentException(
                        "ballot must be a complete total preorder over all candidates");
            }
        }
    }

    private static void requireOrientation(
            List<AnonymousPreferenceBallot> ballots,
            BallotOrientation expected,
            String fieldName) {
        for (var ballot : ballots) {
            Objects.requireNonNull(ballot, fieldName + " element");
            if (ballot.orientation() != expected) {
                throw new IllegalArgumentException(
                        fieldName + " must contain only " + expected + " ballots");
            }
        }
    }

    private static int distinctRoleCount(List<AnonymousPreferenceBallot> ballots) {
        var roles = new LinkedHashSet<LogicalRoleKey>();
        for (var ballot : ballots) {
            roles.add(ballot.logicalRoleKey());
        }
        return roles.size();
    }

    private static void requireSameRoleComposition(
            List<AnonymousPreferenceBallot> forward,
            List<AnonymousPreferenceBallot> reversed) {
        if (!roleSeatCounts(forward).equals(roleSeatCounts(reversed))) {
            throw new IllegalArgumentException(
                    "forward and reversed ballots must contain the same logical-role seat composition");
        }
    }

    private static Map<LogicalRoleKey, Integer> roleSeatCounts(
            List<AnonymousPreferenceBallot> ballots) {
        var counts = new LinkedHashMap<LogicalRoleKey, Integer>();
        for (var ballot : ballots) {
            counts.merge(ballot.logicalRoleKey(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private static List<AnonymousPreferenceBallot> concatenate(
            List<AnonymousPreferenceBallot> left, List<AnonymousPreferenceBallot> right) {
        var combined = new ArrayList<AnonymousPreferenceBallot>(left.size() + right.size());
        combined.addAll(left);
        combined.addAll(right);
        return combined;
    }

    private static List<AnonymousCandidateId> mergeUndefeated(
            Collection<AnonymousCandidateId> left, Collection<AnonymousCandidateId> right) {
        var merged = new LinkedHashSet<AnonymousCandidateId>();
        merged.addAll(left);
        merged.addAll(right);
        var ordered = new ArrayList<>(merged);
        ordered.sort(Comparator.comparing(AnonymousCandidateId::value));
        return List.copyOf(ordered);
    }
}
