package io.omnnu.finbot.domain.consensus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete total preorder over anonymous candidates, attributed to one logical role seat.
 *
 * <p>{@code preferenceTiers} are ordered from most preferred to least preferred. Candidates in
 * the same tier are tied. Every candidate of the election must appear in exactly one tier.
 */
public record AnonymousPreferenceBallot(
        LogicalRoleKey logicalRoleKey,
        BallotOrientation orientation,
        List<Set<AnonymousCandidateId>> preferenceTiers) {

    public AnonymousPreferenceBallot {
        Objects.requireNonNull(logicalRoleKey, "logicalRoleKey");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(preferenceTiers, "preferenceTiers");
        if (preferenceTiers.isEmpty()) {
            throw new IllegalArgumentException("preferenceTiers must not be empty");
        }

        var normalizedTiers = new ArrayList<Set<AnonymousCandidateId>>(preferenceTiers.size());
        var seen = new HashSet<AnonymousCandidateId>();
        for (var tier : preferenceTiers) {
            Objects.requireNonNull(tier, "preferenceTiers element");
            if (tier.isEmpty()) {
                throw new IllegalArgumentException("preference tier must not be empty");
            }
            var normalizedTier = Set.copyOf(tier);
            for (var candidate : normalizedTier) {
                Objects.requireNonNull(candidate, "candidate");
                if (!seen.add(candidate)) {
                    throw new IllegalArgumentException(
                            "duplicate candidate in preference ballot: " + candidate.value());
                }
            }
            normalizedTiers.add(normalizedTier);
        }
        preferenceTiers = List.copyOf(normalizedTiers);
    }

    /**
     * Builds a ballot from ordered tiers. Each tier may list equal-ranked candidates.
     */
    public static AnonymousPreferenceBallot of(
            LogicalRoleKey logicalRoleKey,
            BallotOrientation orientation,
            List<List<AnonymousCandidateId>> tiers) {
        Objects.requireNonNull(tiers, "tiers");
        var preferenceTiers = new ArrayList<Set<AnonymousCandidateId>>(tiers.size());
        for (var tier : tiers) {
            Objects.requireNonNull(tier, "tier");
            preferenceTiers.add(new LinkedHashSet<>(tier));
        }
        return new AnonymousPreferenceBallot(logicalRoleKey, orientation, preferenceTiers);
    }

    /** All candidates ranked by this ballot, in tier-major stable encounter order. */
    public Set<AnonymousCandidateId> candidates() {
        var candidates = new LinkedHashSet<AnonymousCandidateId>();
        for (var tier : preferenceTiers) {
            candidates.addAll(tier);
        }
        return Set.copyOf(candidates);
    }

    /**
     * Pairwise preference: negative when {@code left} is preferred to {@code right}, zero when
     * tied, positive when {@code right} is preferred to {@code left}.
     */
    public int comparePreference(AnonymousCandidateId left, AnonymousCandidateId right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        var ranks = tierIndexByCandidate();
        var leftRank = ranks.get(left);
        var rightRank = ranks.get(right);
        if (leftRank == null || rightRank == null) {
            throw new IllegalArgumentException("candidate not present on this ballot");
        }
        return Integer.compare(leftRank, rightRank);
    }

    public boolean prefers(AnonymousCandidateId preferred, AnonymousCandidateId other) {
        return comparePreference(preferred, other) < 0;
    }

    private Map<AnonymousCandidateId, Integer> tierIndexByCandidate() {
        var ranks = new HashMap<AnonymousCandidateId, Integer>();
        for (var index = 0; index < preferenceTiers.size(); index++) {
            for (var candidate : preferenceTiers.get(index)) {
                ranks.put(candidate, index);
            }
        }
        return ranks;
    }
}
