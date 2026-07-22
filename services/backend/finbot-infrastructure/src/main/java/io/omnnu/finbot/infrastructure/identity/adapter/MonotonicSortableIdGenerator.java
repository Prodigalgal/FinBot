package io.omnnu.finbot.infrastructure.identity.adapter;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class MonotonicSortableIdGenerator implements SortableIdGenerator {
    private static final int TIME_WIDTH = 13;
    private static final int RANDOM_BYTES = 10;

    private final Clock clock;
    private final SecureRandom random;
    private final AtomicLong lastTimestamp = new AtomicLong();

    public MonotonicSortableIdGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    MonotonicSortableIdGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String next(String prefix) {
        var logicalTimestamp = lastTimestamp.updateAndGet(previous -> Math.max(clock.millis(), previous + 1));
        var timePart = Long.toUnsignedString(logicalTimestamp, Character.MAX_RADIX);
        var randomBytes = new byte[RANDOM_BYTES];
        random.nextBytes(randomBytes);
        return prefix + "0".repeat(TIME_WIDTH - timePart.length()) + timePart + "_" + HexFormat.of().formatHex(randomBytes);
    }
}
