package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CrawlerConcurrencyLimiterTest {
    @Test
    @SuppressWarnings("try")
    void rejectsWhenThePerHostCapacityIsExhausted() {
        var limiter = new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofMillis(10));
        try (var ignored = limiter.acquire("source_test_limiter", URI.create("https://example.com/one"))) {
            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> limiter.acquire("source_test_limiter", URI.create("https://example.com/two")));

            assertEquals("CRAWLER_BACKPRESSURE_REJECTED", exception.errorCode());
        }
    }

    @Test
    @SuppressWarnings("try")
    void isolatesCapacityBySourceEvenAcrossDifferentHosts() {
        var limiter = new CrawlerConcurrencyLimiter(3, 1, 3, Duration.ofMillis(10));
        try (var ignored = limiter.acquire("source_alpha_test", URI.create("https://one.example/a"))) {
            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> limiter.acquire("source_alpha_test", URI.create("https://two.example/b")));

            assertEquals("CRAWLER_BACKPRESSURE_REJECTED", exception.errorCode());
            try (var secondSource = limiter.acquire(
                    "source_beta_test",
                    URI.create("https://two.example/b"))) {
                assertEquals(0, limiter.capacity("source_alpha_test").sourceAvailable());
                assertEquals(0, limiter.capacity("source_beta_test").sourceAvailable());
            }
        }
    }

    @Test
    @SuppressWarnings("try")
    void releasesAlreadyAcquiredPermitsWhenWaitingThreadIsInterrupted() throws Exception {
        var limiter = new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(10));
        try (var held = limiter.acquire("source_interrupt_test", URI.create("https://example.com/held"))) {
            var failure = new AtomicReference<Throwable>();
            var waiting = new Thread(() -> {
                try {
                    limiter.acquire("source_interrupt_test", URI.create("https://example.com/wait"));
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            });
            waiting.start();
            Thread.sleep(50);
            waiting.interrupt();
            waiting.join(2_000);

            assertNotNull(failure.get());
            assertEquals("CRAWLER_PERMIT_INTERRUPTED",
                    ((SourceCollectionException) failure.get()).errorCode());
        }

        try (var recovered = limiter.acquire("source_interrupt_test", URI.create("https://example.com/recovered"))) {
            assertEquals(0, limiter.capacity("source_interrupt_test").sourceAvailable());
        }
    }
}
