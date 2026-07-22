package io.omnnu.finbot.application.exchange.port.in;

import io.omnnu.finbot.application.exchange.dto.ExchangeAccountControl;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;

@FunctionalInterface
public interface ExchangeAccountControlUseCase {
    ExchangeAccountControl setEnabled(
            ExchangeAccountId accountId,
            boolean enabled,
            long expectedVersion);
}
