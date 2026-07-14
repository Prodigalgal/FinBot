package io.omnnu.finbot.application.exchange;

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
}
