package io.omnnu.finbot.infrastructure.identity.adapter;

import io.omnnu.finbot.infrastructure.identity.adapter.MonotonicSortableIdGenerator;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MonotonicSortableIdGeneratorTest {
    @Test
    void idsRemainOrderedWithinTheSameClockTick() {
        var generator = new MonotonicSortableIdGenerator(
                Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC),
                new SecureRandom(new byte[] {1, 2, 3, 4}));

        var first = generator.next("run_");
        var second = generator.next("run_");

        assertNotEquals(first, second);
        assertTrue(first.compareTo(second) < 0);
    }
}
