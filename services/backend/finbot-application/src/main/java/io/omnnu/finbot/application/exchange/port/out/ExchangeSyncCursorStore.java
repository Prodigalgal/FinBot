package io.omnnu.finbot.application.exchange.port.out;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Instant;
import java.util.Optional;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import java.util.List;

public interface ExchangeSyncCursorStore {
    Optional<Instant> watermark(ExchangeAccountId accountId);

    void advance(ExchangeAccountId accountId, Instant watermark, Instant updatedAt);

    List<InstrumentSymbol> currentOpenPositionSymbols(ExchangeAccountId accountId);
}
