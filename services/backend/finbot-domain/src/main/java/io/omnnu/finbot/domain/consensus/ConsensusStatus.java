package io.omnnu.finbot.domain.consensus;

/**
 * Terminal social-choice status for SDB-SCA consensus.
 *
 * <p>{@link #SELECTED} is the only executable success state.
 */
public enum ConsensusStatus {
    /** Forward and reversed orientations share the same unique strict Schulze winner. */
    SELECTED,
    /** More than one undefeated candidate remains after strongest-path comparison. */
    TIED,
    /** Distinct logical roles with valid ballots are below the configured quorum. */
    LOW_QUORUM,
    /** Both orientations produce unique winners, but the winners differ. */
    ORDER_SENSITIVE,
    /** No ballots remain after validation of the orientation inputs. */
    NO_VALID_BALLOTS,
    /** A maximal candidate exists but does not strictly beat every other candidate. */
    NO_STRICT_WINNER
}
