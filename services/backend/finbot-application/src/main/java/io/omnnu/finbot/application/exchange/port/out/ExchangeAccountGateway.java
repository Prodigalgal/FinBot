package io.omnnu.finbot.application.exchange.port.out;

import io.omnnu.finbot.application.exchange.dto.ExchangeAccountSyncBatch;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Instant;

@FunctionalInterface
public interface ExchangeAccountGateway {
    ExchangeAccountSyncBatch synchronize(
            ExchangeAccountId accountId,
            Instant fromInclusive,
            Instant toExclusive);
}
