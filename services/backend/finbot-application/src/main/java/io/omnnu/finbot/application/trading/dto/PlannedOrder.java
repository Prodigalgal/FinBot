package io.omnnu.finbot.application.trading.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.trading.ApprovedTradeIntentId;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.time.Instant;

public record PlannedOrder(
        OrderId orderId,
        ApprovedTradeIntentId intentId,
        String idempotencyKey,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        ExchangeAccountId accountId,
        InstrumentId instrumentId,
        InstrumentSymbol symbol,
        DirectionalAction side,
        BigDecimal quantity,
        BigDecimal leverage,
        String clientOrderId,
        Instant createdAt) {
}
