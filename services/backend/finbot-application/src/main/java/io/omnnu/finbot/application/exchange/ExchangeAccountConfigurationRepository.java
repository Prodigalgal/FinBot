package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.Optional;

@FunctionalInterface
public interface ExchangeAccountConfigurationRepository {
    Optional<ExchangeAccountConfiguration> find(ExchangeAccountId accountId);
}
