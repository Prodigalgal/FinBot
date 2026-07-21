package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public final class ProviderConcurrencyLimiter {
    private final ConcurrentHashMap<AiProviderProfileId, ProviderGate> providerGates =
            new ConcurrentHashMap<>();

    public Permit acquire(
            AiProviderProfileId providerProfileId,
            int maximumConcurrentRequests,
            long configurationVersion,
            Duration acquireTimeout) throws InterruptedException {
        Objects.requireNonNull(providerProfileId, "providerProfileId");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (maximumConcurrentRequests < 1 || maximumConcurrentRequests > 32) {
            throw new IllegalArgumentException("maximumConcurrentRequests must be between 1 and 32");
        }
        if (configurationVersion < 0) {
            throw new IllegalArgumentException("configurationVersion must not be negative");
        }
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()
                || acquireTimeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("acquireTimeout must be positive and at most two hours");
        }
        var gate = providerGates.computeIfAbsent(
                providerProfileId,
                ignored -> new ProviderGate(maximumConcurrentRequests, configurationVersion));
        if (!gate.acquire(maximumConcurrentRequests, configurationVersion, acquireTimeout)) {
            throw new ProviderCapacityTimeoutException(providerProfileId);
        }
        return new Permit(gate);
    }

    int activeCount(AiProviderProfileId providerProfileId) {
        var gate = providerGates.get(providerProfileId);
        return gate == null ? 0 : gate.activeCount();
    }

    int queueDepth(AiProviderProfileId providerProfileId) {
        var gate = providerGates.get(providerProfileId);
        return gate == null ? 0 : gate.queueDepth();
    }

    int configuredLimit(AiProviderProfileId providerProfileId) {
        var gate = providerGates.get(providerProfileId);
        return gate == null ? 0 : gate.configuredLimit();
    }

    private static final class ProviderGate {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition capacityAvailable = lock.newCondition();
        private int configuredLimit;
        private long configurationVersion;
        private int active;
        private int waiting;

        private ProviderGate(int configuredLimit, long configurationVersion) {
            this.configuredLimit = configuredLimit;
            this.configurationVersion = configurationVersion;
        }

        private boolean acquire(
                int requestedLimit,
                long requestedConfigurationVersion,
                Duration acquireTimeout) throws InterruptedException {
            long remainingNanos = acquireTimeout.toNanos();
            lock.lockInterruptibly();
            try {
                applyConfiguration(requestedLimit, requestedConfigurationVersion);
                waiting++;
                try {
                    while (active >= configuredLimit) {
                        if (remainingNanos <= 0) {
                            return false;
                        }
                        remainingNanos = capacityAvailable.awaitNanos(remainingNanos);
                    }
                    active++;
                    return true;
                } finally {
                    waiting--;
                }
            } finally {
                lock.unlock();
            }
        }

        private void applyConfiguration(int requestedLimit, long requestedConfigurationVersion) {
            if (requestedConfigurationVersion <= configurationVersion) {
                return;
            }
            configuredLimit = requestedLimit;
            configurationVersion = requestedConfigurationVersion;
            capacityAvailable.signalAll();
        }

        private int activeCount() {
            lock.lock();
            try {
                return active;
            } finally {
                lock.unlock();
            }
        }

        private int queueDepth() {
            lock.lock();
            try {
                return waiting;
            } finally {
                lock.unlock();
            }
        }

        private int configuredLimit() {
            lock.lock();
            try {
                return configuredLimit;
            } finally {
                lock.unlock();
            }
        }

        private void release() {
            lock.lock();
            try {
                if (active <= 0) {
                    throw new IllegalStateException("Provider concurrency permit was released more than once");
                }
                active--;
                capacityAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    public static final class Permit implements AutoCloseable {
        private final ProviderGate gate;
        private boolean released;

        private Permit(ProviderGate gate) {
            this.gate = gate;
        }

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                gate.release();
            }
        }
    }

    public static final class ProviderCapacityTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ProviderCapacityTimeoutException(AiProviderProfileId providerProfileId) {
            super("Provider concurrency capacity timed out: " + providerProfileId.value());
        }
    }
}
