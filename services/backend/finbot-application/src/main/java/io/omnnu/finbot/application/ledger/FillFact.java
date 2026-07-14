package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.ledger.LedgerOrderSide;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import java.math.BigDecimal;
import java.time.Instant;

public record FillFact(
        LedgerFactId factId,
        ExchangeAccountId accountId,
        String sourceEventId,
        String exchangeFillId,
        String exchangeOrderId,
        String clientOrderId,
        InstrumentSymbol symbol,
        LedgerOrderSide side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        String feeCurrency,
        BigDecimal realizedPnl,
        Instant occurredAt,
        Instant receivedAt) {
    public FillFact {
        java.util.Objects.requireNonNull(factId, "factId");
        java.util.Objects.requireNonNull(accountId, "accountId");
        sourceEventId = LedgerValidation.required(sourceEventId, "sourceEventId", 160);
        exchangeFillId = LedgerValidation.required(exchangeFillId, "exchangeFillId", 160);
        exchangeOrderId = LedgerValidation.required(exchangeOrderId, "exchangeOrderId", 160);
        clientOrderId = LedgerValidation.optional(clientOrderId, "clientOrderId", 160);
        java.util.Objects.requireNonNull(symbol, "symbol");
        java.util.Objects.requireNonNull(side, "side");
        quantity = LedgerValidation.positive(quantity, "quantity");
        price = LedgerValidation.positive(price, "price");
        fee = LedgerValidation.nonNegative(fee, "fee");
        feeCurrency = LedgerValidation.currency(feeCurrency);
        if (realizedPnl != null) {
            realizedPnl = io.omnnu.finbot.domain.shared.DecimalValue.finite(realizedPnl, "realizedPnl");
        }
        LedgerValidation.requireTimeline(occurredAt, receivedAt);
    }
}
