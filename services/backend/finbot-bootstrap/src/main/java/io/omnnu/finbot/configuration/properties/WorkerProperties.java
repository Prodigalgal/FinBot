package io.omnnu.finbot.configuration.properties;

import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finbot.worker")
public record WorkerProperties(
        Duration pollDelay,
        Duration leaseDuration,
        Duration heartbeatInterval,
        Duration leaseRecoveryInterval,
        Duration retryDelay,
        Duration schedulerPollDelay,
        int maximumDueSchedules,
        int maximumConcurrentTasks,
        Map<BackgroundTaskType, Integer> maximumConcurrentByType) {
    public WorkerProperties {
        requirePositive(pollDelay, "pollDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(leaseRecoveryInterval, "leaseRecoveryInterval");
        requirePositive(retryDelay, "retryDelay");
        requirePositive(schedulerPollDelay, "schedulerPollDelay");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("heartbeatInterval must be shorter than leaseDuration");
        }
        if (maximumDueSchedules < 1 || maximumDueSchedules > 100) {
            throw new IllegalArgumentException("maximumDueSchedules must be between 1 and 100");
        }
        if (maximumConcurrentTasks < 1 || maximumConcurrentTasks > 64) {
            throw new IllegalArgumentException("maximumConcurrentTasks must be between 1 and 64");
        }
        var limits = new EnumMap<BackgroundTaskType, Integer>(BackgroundTaskType.class);
        limits.putAll(Objects.requireNonNull(maximumConcurrentByType, "maximumConcurrentByType"));
        for (var type : BackgroundTaskType.values()) {
            var limit = limits.get(type);
            if (limit == null || limit < 1 || limit > maximumConcurrentTasks) {
                throw new IllegalArgumentException(
                        "maximumConcurrentByType[" + type + "] must be between 1 and maximumConcurrentTasks");
            }
        }
        maximumConcurrentByType = Map.copyOf(limits);
    }

    public int maximumConcurrentTasks(BackgroundTaskType taskType) {
        return maximumConcurrentByType.get(taskType);
    }

    private static void requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
