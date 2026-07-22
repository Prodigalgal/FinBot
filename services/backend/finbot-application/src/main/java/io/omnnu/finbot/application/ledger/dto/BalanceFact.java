package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.application.ledger.dto.LedgerValidation;

import io.omnnu.finbot.domain.ledger.BalanceChangeReason;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import java.math.BigDecimal;
import java.time.Instant;

public record BalanceFact(
        LedgerFactId factId,
        ExchangeAccountId accountId,
        String sourceEventId,
        String currency,
        BigDecimal total,
        BigDecimal available,
        BigDecimal changeAmount,
        BalanceChangeReason reason,
        Instant occurredAt,
        Instant receivedAt) {
    public BalanceFact {
        java.util.Objects.requireNonNull(factId, "factId");
        java.util.Objects.requireNonNull(accountId, "accountId");
        sourceEventId = LedgerValidation.required(sourceEventId, "sourceEventId", 160);
        currency = LedgerValidation.currency(currency);
        total = LedgerValidation.nonNegative(total, "total");
        available = LedgerValidation.nonNegative(available, "available");
        if (changeAmount != null) {
            changeAmount = io.omnnu.finbot.domain.shared.DecimalValue.finite(changeAmount, "changeAmount");
        }
        java.util.Objects.requireNonNull(reason, "reason");
        LedgerValidation.requireTimeline(occurredAt, receivedAt);
    }
}
