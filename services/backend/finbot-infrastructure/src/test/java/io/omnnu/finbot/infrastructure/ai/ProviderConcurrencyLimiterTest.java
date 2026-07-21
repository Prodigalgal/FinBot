package io.omnnu.finbot.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ProviderConcurrencyLimiterTest {
    private static final AiProviderProfileId PROVIDER =
            new AiProviderProfileId("provider_concurrency_test");

    @Test
    void queuesFairlyUntilProviderCapacityIsReleased() throws Exception {
        var limiter = new ProviderConcurrencyLimiter(1, Duration.ofSeconds(5));
        var first = limiter.acquire(PROVIDER, Duration.ofSeconds(5));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = CompletableFuture.supplyAsync(() -> acquire(limiter), executor);

            while (limiter.queueDepth(PROVIDER) == 0) {
                Thread.onSpinWait();
            }
            assertEquals(1, limiter.activeCount(PROVIDER));
            assertFalse(waiting.isDone());
            first.close();
            var second = waiting.get();
            try {
                assertEquals(1, limiter.activeCount(PROVIDER));
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
        assertEquals(0, limiter.activeCount(PROVIDER));
    }

    @Test
    void failsWhenQueueWaitExceedsConfiguredTimeout() throws Exception {
        var limiter = new ProviderConcurrencyLimiter(1, Duration.ofSeconds(5));
        var permit = limiter.acquire(PROVIDER, Duration.ofSeconds(5));
        try {
            assertThrows(
                    ProviderConcurrencyLimiter.ProviderCapacityTimeoutException.class,
                    () -> limiter.acquire(PROVIDER, Duration.ofMillis(50)));
        } finally {
            permit.close();
        }
    }

    private static ProviderConcurrencyLimiter.Permit acquire(ProviderConcurrencyLimiter limiter) {
        try {
            return limiter.acquire(PROVIDER, Duration.ofSeconds(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
