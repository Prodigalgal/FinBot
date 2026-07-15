package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.TradingActivityType;

public record TradingActivityCount(TradingActivityType activityType, long count) {
    public TradingActivityCount {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
    }
}
