package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.TradingActivityType;
import io.omnnu.finbot.domain.ledger.TradingActivitySource;

public record TradingActivityCriteria(
        ExchangeAccountId accountId,
        TradingActivitySource source,
        TradingActivityType activityType,
        String status,
        String symbol,
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
        status = normalize(status, 80, "status");
        symbol = normalize(symbol, 48, "symbol");
    }

    private static String normalize(String value, int maximumLength, String name) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        return normalized;
    }
}
