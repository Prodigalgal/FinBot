package io.omnnu.finbot.domain.consensus;

/**
 * Presentation orientation of an anonymous preference ballot.
 *
 * <p>Forward and reversed ballots are aggregated independently; only matching unique strict
 * winners yield {@link ConsensusStatus#SELECTED}.
 */
public enum BallotOrientation {
    FORWARD,
    REVERSED
}
