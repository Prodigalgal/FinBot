package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;

public record PositionView(
        ExchangeAccountId accountId,
        String symbol,
        PositionSide side,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal markPrice,
        BigDecimal liquidationPrice,
        BigDecimal leverage,
        BigDecimal unrealizedPnl,
        BigDecimal margin,
        Instant occurredAt) {
}
