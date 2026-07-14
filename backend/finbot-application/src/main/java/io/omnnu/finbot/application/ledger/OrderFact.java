package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeOrderStatus;
import io.omnnu.finbot.domain.ledger.ExchangeOrderType;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.ledger.LedgerOrderSide;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderFact(
        LedgerFactId factId,
        ExchangeAccountId accountId,
        String sourceEventId,
        String exchangeOrderId,
        String clientOrderId,
        InstrumentSymbol symbol,
        LedgerOrderSide side,
        ExchangeOrderType orderType,
        ExchangeOrderStatus status,
        BigDecimal quantity,
        BigDecimal filledQuantity,
        BigDecimal limitPrice,
        BigDecimal averageFillPrice,
        boolean reduceOnly,
        Instant occurredAt,
        Instant receivedAt) {
    public OrderFact {
        java.util.Objects.requireNonNull(factId, "factId");
        java.util.Objects.requireNonNull(accountId, "accountId");
        sourceEventId = LedgerValidation.required(sourceEventId, "sourceEventId", 160);
        exchangeOrderId = LedgerValidation.required(exchangeOrderId, "exchangeOrderId", 160);
        clientOrderId = LedgerValidation.optional(clientOrderId, "clientOrderId", 160);
        java.util.Objects.requireNonNull(symbol, "symbol");
        java.util.Objects.requireNonNull(side, "side");
        java.util.Objects.requireNonNull(orderType, "orderType");
        java.util.Objects.requireNonNull(status, "status");
        quantity = LedgerValidation.positive(quantity, "quantity");
        filledQuantity = LedgerValidation.nonNegative(filledQuantity, "filledQuantity");
        if (filledQuantity.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("filledQuantity must not exceed quantity");
        }
        limitPrice = LedgerValidation.optionalPositive(limitPrice, "limitPrice");
        averageFillPrice = LedgerValidation.optionalPositive(averageFillPrice, "averageFillPrice");
        LedgerValidation.requireTimeline(occurredAt, receivedAt);
    }
}
