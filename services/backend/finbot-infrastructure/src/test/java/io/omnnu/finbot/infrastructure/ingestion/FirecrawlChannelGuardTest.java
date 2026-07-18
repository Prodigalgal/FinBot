package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FirecrawlChannelGuardTest {
    private static final SourceId SOURCE_ID = new SourceId("source_firecrawl_guard");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T08:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void opensCircuitAfterTheConfiguredNumberOfFailures() {
        var guard = new FirecrawlChannelGuard(10, 2, Duration.ofMinutes(15), CLOCK);
        var invocations = new AtomicInteger();

        for (var attempt = 0; attempt < 2; attempt++) {
            assertThrows(IllegalStateException.class, () -> guard.execute(SOURCE_ID, () -> {
                invocations.incrementAndGet();
                throw new IllegalStateException("provider unavailable");
            }));
        }
        var exception = assertThrows(
                SourceCollectionException.class,
                () -> guard.execute(SOURCE_ID, () -> {
                    invocations.incrementAndGet();
                    return "unexpected";
                }));

        assertEquals("FIRECRAWL_CIRCUIT_OPEN", exception.errorCode());
        assertEquals(2, invocations.get());
        assertEquals("CIRCUIT_OPEN", guard.snapshot(SOURCE_ID).status());
    }

    @Test
    void rejectsCallsAfterTheDailySourceBudgetIsConsumed() {
        var guard = new FirecrawlChannelGuard(2, 3, Duration.ofMinutes(15), CLOCK);

        assertEquals("ok", guard.execute(SOURCE_ID, () -> "ok"));
        assertEquals("ok", guard.execute(SOURCE_ID, () -> "ok"));
        var exception = assertThrows(
                SourceCollectionException.class,
                () -> guard.execute(SOURCE_ID, () -> "unexpected"));

        assertEquals("FIRECRAWL_DAILY_BUDGET_EXHAUSTED", exception.errorCode());
        assertEquals("BUDGET_EXHAUSTED", guard.snapshot(SOURCE_ID).status());
        assertEquals(0, guard.snapshot(SOURCE_ID).remainingCallsToday());
    }
}
