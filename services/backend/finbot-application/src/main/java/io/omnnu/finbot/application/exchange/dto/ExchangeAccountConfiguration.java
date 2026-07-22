package io.omnnu.finbot.application.exchange.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;

public record ExchangeAccountConfiguration(
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String apiKeyEnvironmentVariable,
        String apiSecretEnvironmentVariable,
        boolean enabled) {
    public ExchangeAccountConfiguration {
        if (environment == ExchangeEnvironment.LIVE) {
            throw new IllegalArgumentException("Live exchange accounts are outside the current execution boundary");
        }
    }
}
