package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CrawlerConcurrencyLimiterTest {
    @Test
    @SuppressWarnings("try")
    void rejectsWhenThePerHostCapacityIsExhausted() {
        var limiter = new CrawlerConcurrencyLimiter(2, 1, Duration.ofMillis(10));
        try (var ignored = limiter.acquire(URI.create("https://example.com/one"))) {
            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> limiter.acquire(URI.create("https://example.com/two")));

            assertEquals("CRAWLER_BACKPRESSURE_REJECTED", exception.errorCode());
        }
    }
}
