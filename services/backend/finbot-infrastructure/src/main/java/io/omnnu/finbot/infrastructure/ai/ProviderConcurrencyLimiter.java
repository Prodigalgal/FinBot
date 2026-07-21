package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ProviderConcurrencyLimiter {
    private final int maximumConcurrentPerProvider;
    private final Duration acquireTimeout;
    private final ConcurrentHashMap<AiProviderProfileId, Semaphore> providerPermits =
            new ConcurrentHashMap<>();

    public ProviderConcurrencyLimiter(
            @Value("${FINBOT_AI_MAX_CONCURRENT_PER_PROVIDER:2}") int maximumConcurrentPerProvider,
            @Value("${FINBOT_AI_PROVIDER_ACQUIRE_TIMEOUT:PT15M}") Duration acquireTimeout) {
        if (maximumConcurrentPerProvider < 1 || maximumConcurrentPerProvider > 32) {
            throw new IllegalArgumentException("maximumConcurrentPerProvider must be between 1 and 32");
        }
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (acquireTimeout.compareTo(Duration.ofSeconds(5)) < 0
                || acquireTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("acquireTimeout must be between five and thirty minutes");
        }
        this.maximumConcurrentPerProvider = maximumConcurrentPerProvider;
    }

    public Permit acquire(
            AiProviderProfileId providerProfileId,
            Duration requestTimeout) throws InterruptedException {
        Objects.requireNonNull(providerProfileId, "providerProfileId");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        var semaphore = providerPermits.computeIfAbsent(
                providerProfileId,
                ignored -> new Semaphore(maximumConcurrentPerProvider, true));
        var waitTimeout = minimum(acquireTimeout, requestTimeout);
        if (!semaphore.tryAcquire(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new ProviderCapacityTimeoutException(providerProfileId);
        }
        return new Permit(semaphore);
    }

    int activeCount(AiProviderProfileId providerProfileId) {
        var semaphore = providerPermits.get(providerProfileId);
        return semaphore == null ? 0 : maximumConcurrentPerProvider - semaphore.availablePermits();
    }

    int queueDepth(AiProviderProfileId providerProfileId) {
        var semaphore = providerPermits.get(providerProfileId);
        return semaphore == null ? 0 : semaphore.getQueueLength();
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean released;

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
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
