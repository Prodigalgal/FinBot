package io.omnnu.finbot.application.identity;

import java.time.Duration;
import java.util.Objects;

public record AuthenticationPolicy(
        Duration challengeTtl,
        int proofOfWorkDifficulty,
        int maximumChallengeFailures,
        Duration sessionTtl,
        Duration touchInterval) {
    public AuthenticationPolicy {
        Objects.requireNonNull(challengeTtl, "challengeTtl");
        Objects.requireNonNull(sessionTtl, "sessionTtl");
        Objects.requireNonNull(touchInterval, "touchInterval");
        if (challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException("challengeTtl must be positive");
        }
        if (proofOfWorkDifficulty < 1 || proofOfWorkDifficulty > 8) {
            throw new IllegalArgumentException("proofOfWorkDifficulty must be between 1 and 8");
        }
        if (maximumChallengeFailures < 1 || maximumChallengeFailures > 10) {
            throw new IllegalArgumentException("maximumChallengeFailures must be between 1 and 10");
        }
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
        if (touchInterval.isNegative()) {
            throw new IllegalArgumentException("touchInterval must not be negative");
        }
    }
}
