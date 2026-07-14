package io.omnnu.finbot.configuration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finbot.worker")
public record WorkerProperties(
        Duration pollDelay,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration retryDelay,
        Duration schedulerPollDelay,
        int maximumDueSchedules) {
    public WorkerProperties {
        requirePositive(pollDelay, "pollDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(retryDelay, "retryDelay");
        requirePositive(schedulerPollDelay, "schedulerPollDelay");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseDuration");
        }
        if (maximumDueSchedules < 1 || maximumDueSchedules > 100) {
            throw new IllegalArgumentException("maximumDueSchedules must be between 1 and 100");
        }
    }

    private static void requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
