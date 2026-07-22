package io.omnnu.finbot.application.exchange.port.out;

import io.omnnu.finbot.application.exchange.dto.ExchangeAccountConfiguration;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.Optional;

@FunctionalInterface
public interface ExchangeAccountConfigurationRepository {
    Optional<ExchangeAccountConfiguration> find(ExchangeAccountId accountId);
}
