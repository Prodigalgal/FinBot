package io.omnnu.finbot.domain.consensus;

import java.util.Objects;

/**
 * Full Schulze social-choice result including the terminal outcome and per-orientation audit
 * snapshots.
 *
 * <p>Both orientation snapshots are always present with non-null, deeply immutable matrices so
 * unsuccessful paths (empty ballots, low quorum, ties, order-sensitivity) remain auditable.
 */
public record SchulzeDetailedResult(
        SchulzeOutcome outcome,
        SchulzeOrientationSnapshot forward,
        SchulzeOrientationSnapshot reversed) {

    public SchulzeDetailedResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(reversed, "reversed");
    }
}
