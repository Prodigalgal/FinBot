package io.omnnu.finbot.application.exchange.dto;

import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.oms.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record OmsReconciliationCandidate(
        OrderId orderId,
        OrderStatus currentStatus,
        OrderStatus exchangeStatus,
        String exchangeOrderId,
        BigDecimal filledQuantity,
        BigDecimal averageFillPrice,
        Instant exchangeOccurredAt) {
}
