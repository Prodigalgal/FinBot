package io.omnnu.finbot.domain.quant;

import java.time.Instant;
import java.util.Objects;

public record ResearchTimeRange(Instant startInclusive, Instant endExclusive) {
    public ResearchTimeRange {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must be before endExclusive");
        }
    }
}
