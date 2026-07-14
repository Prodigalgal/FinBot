package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.time.Instant;

public record ExchangeAccountProfile(
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String displayName,
        String apiKeyEnv,
        String apiSecretEnv,
        String proxyRoute,
        boolean enabled,
        Instant updatedAt) {
}
