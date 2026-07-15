package io.omnnu.finbot.application.ledger;

import java.util.List;

public record TradingActivityPage(
        List<TradingActivity> activities,
        TradingActivityCursor nextCursor,
        long matchedCount,
        List<TradingActivityCount> counts,
        List<TradingActivitySourceStatus> sources) {
    public TradingActivityPage {
        activities = List.copyOf(activities);
        counts = List.copyOf(counts);
        sources = List.copyOf(sources);
        if (matchedCount < 0) {
            throw new IllegalArgumentException("matchedCount must not be negative");
        }
    }
}
