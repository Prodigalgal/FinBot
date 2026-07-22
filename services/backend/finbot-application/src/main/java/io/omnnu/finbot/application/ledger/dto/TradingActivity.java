package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.TradingActivitySource;
import io.omnnu.finbot.domain.ledger.TradingActivityType;
import java.math.BigDecimal;
import java.time.Instant;

public record TradingActivity(
        String activityId,
        String sourceEventId,
        TradingActivityType activityType,
        TradingActivitySource source,
        ExchangeAccountId accountId,
        ExchangeVenue exchange,
        String symbol,
        String status,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount,
        String currency,
        String exchangeOrderId,
        String clientOrderId,
        String title,
        String detail,
        String detailsJson,
        Instant occurredAt,
        Instant receivedAt) {
}
