package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Instant;
import java.util.Optional;

public interface ExchangeAccountControlRepository {
    Optional<ExchangeAccountControl> find(ExchangeAccountId accountId);

    Optional<ExchangeAccountControl> setEnabled(
            ExchangeAccountId accountId,
            boolean enabled,
            long expectedVersion,
            Instant updatedAt);
}
