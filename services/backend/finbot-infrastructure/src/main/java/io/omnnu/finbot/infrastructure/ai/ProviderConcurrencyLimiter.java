package io.omnnu.finbot.infrastructure.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public final class ProviderConcurrencyLimiter {
    private static final int MAXIMUM_WAITERS_PER_PERMIT = 5;

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<AiProviderProfileId, ProviderGate> providerGates =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AiProviderProfileId, ProviderMetrics> providerMetrics =
            new ConcurrentHashMap<>();

    public ProviderConcurrencyLimiter(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

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
                ignored -> createGate(providerProfileId, maximumConcurrentRequests, configurationVersion));
        var metrics = providerMetrics.get(providerProfileId);
        var started = System.nanoTime();
        try {
            if (!gate.acquire(maximumConcurrentRequests, configurationVersion, acquireTimeout)) {
                metrics.capacityTimeouts().increment();
                throw new ProviderCapacityTimeoutException(providerProfileId);
            }
            metrics.queueWait().record(Duration.ofNanos(System.nanoTime() - started));
        } catch (ProviderQueueFullException exception) {
            metrics.queueRejections().increment();
            throw exception;
        }
        return new Permit(gate);
    }

    private ProviderGate createGate(
            AiProviderProfileId providerProfileId,
            int maximumConcurrentRequests,
            long configurationVersion) {
        var gate = new ProviderGate(maximumConcurrentRequests, configurationVersion);
        var providerTag = providerProfileId.value();
        Gauge.builder("finbot.ai.provider.active", gate, ProviderGate::activeCount)
                .tag("provider.profile.id", providerTag)
                .description("Active AI requests holding provider permits")
                .register(meterRegistry);
        Gauge.builder("finbot.ai.provider.queue.depth", gate, ProviderGate::queueDepth)
                .tag("provider.profile.id", providerTag)
                .description("AI requests waiting for provider permits")
                .register(meterRegistry);
        Gauge.builder("finbot.ai.provider.concurrency.limit", gate, ProviderGate::configuredLimit)
                .tag("provider.profile.id", providerTag)
                .description("Configured concurrent AI request limit")
                .register(meterRegistry);
        providerMetrics.put(providerProfileId, new ProviderMetrics(
                Counter.builder("finbot.ai.provider.queue.rejected")
                        .tag("provider.profile.id", providerTag)
                        .description("AI requests rejected because the provider queue was full")
                        .register(meterRegistry),
                Counter.builder("finbot.ai.provider.capacity.timeout")
                        .tag("provider.profile.id", providerTag)
                        .description("AI requests that exceeded the provider capacity wait deadline")
                        .register(meterRegistry),
                Timer.builder("finbot.ai.provider.queue.wait")
                        .tag("provider.profile.id", providerTag)
                        .description("Time spent waiting for an AI provider permit")
                        .register(meterRegistry)));
        return gate;
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
                var maximumWaiters = Math.multiplyExact(configuredLimit, MAXIMUM_WAITERS_PER_PERMIT);
                if (active >= configuredLimit && waiting >= maximumWaiters) {
                    throw new ProviderQueueFullException(maximumWaiters);
                }
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
            var previousLimit = configuredLimit;
            configuredLimit = requestedLimit;
            configurationVersion = requestedConfigurationVersion;
            if (configuredLimit > previousLimit) {
                capacityAvailable.signalAll();
            }
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
                capacityAvailable.signal();
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

    public static final class ProviderQueueFullException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ProviderQueueFullException(int maximumWaiters) {
            super("Provider concurrency queue is full at " + maximumWaiters + " waiting requests");
        }
    }

    private record ProviderMetrics(
            Counter queueRejections,
            Counter capacityTimeouts,
            Timer queueWait) {
    }
}
