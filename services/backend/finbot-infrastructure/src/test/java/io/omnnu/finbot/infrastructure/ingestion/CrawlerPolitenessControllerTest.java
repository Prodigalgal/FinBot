package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CrawlerPolitenessControllerTest {
    @Test
    void reservesRequestsForTheSameHostAtTheConfiguredInterval() {
        var controller = new CrawlerPolitenessController(
                Duration.ofMillis(250),
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC));
        var firstTarget = URI.create("https://example.com/one");
        var secondTarget = URI.create("https://example.com/two");

        assertEquals(Duration.ZERO, controller.reserveDelay(firstTarget));
        assertEquals(Duration.ofMillis(250), controller.reserveDelay(secondTarget));
        assertEquals(Duration.ofMillis(500), controller.reserveDelay(firstTarget));
        assertEquals(1, controller.status().trackedHostCount());
    }
}
