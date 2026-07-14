package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.ledger.PositionSide;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import java.math.BigDecimal;
import java.time.Instant;

public record PositionSnapshotFact(
        LedgerFactId snapshotId,
        ExchangeAccountId accountId,
        String sourceEventId,
        InstrumentSymbol symbol,
        PositionSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal markPrice,
        BigDecimal liquidationPrice,
        BigDecimal leverage,
        BigDecimal unrealizedPnl,
        BigDecimal margin,
        Instant occurredAt,
        Instant receivedAt) {
    public PositionSnapshotFact {
        java.util.Objects.requireNonNull(snapshotId, "snapshotId");
        java.util.Objects.requireNonNull(accountId, "accountId");
        sourceEventId = LedgerValidation.required(sourceEventId, "sourceEventId", 160);
        java.util.Objects.requireNonNull(symbol, "symbol");
        java.util.Objects.requireNonNull(side, "side");
        quantity = LedgerValidation.nonNegative(quantity, "quantity");
        entryPrice = LedgerValidation.optionalPositive(entryPrice, "entryPrice");
        markPrice = LedgerValidation.optionalPositive(markPrice, "markPrice");
        liquidationPrice = LedgerValidation.optionalPositive(liquidationPrice, "liquidationPrice");
        leverage = LedgerValidation.positive(leverage, "leverage");
        if (leverage.compareTo(java.math.BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("leverage must be at least one");
        }
        unrealizedPnl = io.omnnu.finbot.domain.shared.DecimalValue.finite(unrealizedPnl, "unrealizedPnl");
        margin = LedgerValidation.nonNegative(margin, "margin");
        if ((side == io.omnnu.finbot.domain.ledger.PositionSide.FLAT) != (quantity.signum() == 0)) {
            throw new IllegalArgumentException("FLAT position must have zero quantity and vice versa");
        }
        LedgerValidation.requireTimeline(occurredAt, receivedAt);
    }
}
