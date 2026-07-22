package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.TradingActivitySource;
import java.time.Instant;
import java.util.Objects;

public record TradingActivitySourceStatus(
        TradingActivitySource source,
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        String status,
        boolean complete,
        String message,
        Instant latestAt) {

    public TradingActivitySourceStatus {
        Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status").strip();
        message = Objects.requireNonNull(message, "message").strip();
        if (status.isEmpty() || message.isEmpty()) {
            throw new IllegalArgumentException("Source status and message must not be blank");
        }
    }
}
