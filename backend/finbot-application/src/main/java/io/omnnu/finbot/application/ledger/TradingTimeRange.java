package io.omnnu.finbot.application.ledger;

import java.time.Instant;
import java.util.Objects;

public record TradingTimeRange(Instant fromInclusive, Instant toExclusive) {
    public TradingTimeRange {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!toExclusive.isAfter(fromInclusive)) {
            throw new IllegalArgumentException("toExclusive must be after fromInclusive");
        }
    }
}
