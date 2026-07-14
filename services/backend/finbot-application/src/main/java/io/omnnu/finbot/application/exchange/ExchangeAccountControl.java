package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.time.Instant;
import java.util.Objects;

public record ExchangeAccountControl(
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String displayName,
        boolean enabled,
        long version,
        Instant updatedAt) {
    public ExchangeAccountControl {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(environment, "environment");
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (displayName.isEmpty() || version < 0) {
            throw new IllegalArgumentException("Invalid exchange account control state");
        }
    }
}
