package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.TradingActivityType;

public record TradingActivityCriteria(
        ExchangeAccountId accountId,
        TradingActivityType activityType,
        TradingTimeRange range,
        TradingActivityCursor before,
        int limit) {
    public TradingActivityCriteria {
        if (range == null) {
            throw new NullPointerException("range");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
