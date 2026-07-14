package io.omnnu.finbot.domain.workflow;

import java.time.Duration;
import java.util.Objects;

public record WorkflowRetryPolicy(int maximumAttempts, Duration backoff) {
    public WorkflowRetryPolicy {
        Objects.requireNonNull(backoff, "backoff");
        if (maximumAttempts < 1 || maximumAttempts > 5) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 5");
        }
        if (backoff.isNegative() || backoff.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("backoff must be between zero and five minutes");
        }
    }
}
