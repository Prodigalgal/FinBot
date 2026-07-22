package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.application.ledger.dto.LedgerValidation;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.ledger.PnlSourceType;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Money;
import java.time.Instant;

public record RealizedPnlFact(
        LedgerFactId factId,
        ExchangeAccountId accountId,
        String sourceEventId,
        InstrumentSymbol symbol,
        Money amount,
        PnlSourceType sourceType,
        String relatedOrderId,
        String relatedFillId,
        Instant occurredAt,
        Instant receivedAt) {
    public RealizedPnlFact {
        java.util.Objects.requireNonNull(factId, "factId");
        java.util.Objects.requireNonNull(accountId, "accountId");
        sourceEventId = LedgerValidation.required(sourceEventId, "sourceEventId", 160);
        java.util.Objects.requireNonNull(symbol, "symbol");
        java.util.Objects.requireNonNull(amount, "amount");
        java.util.Objects.requireNonNull(sourceType, "sourceType");
        relatedOrderId = LedgerValidation.optional(relatedOrderId, "relatedOrderId", 160);
        relatedFillId = LedgerValidation.optional(relatedFillId, "relatedFillId", 160);
        LedgerValidation.requireTimeline(occurredAt, receivedAt);
    }
}
