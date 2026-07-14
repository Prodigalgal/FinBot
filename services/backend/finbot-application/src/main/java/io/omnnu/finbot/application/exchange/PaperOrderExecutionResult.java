package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.oms.OrderId;

public record PaperOrderExecutionResult(
        OrderId orderId,
        ExchangeSubmissionStatus status,
        String exchangeOrderId,
        String safeMessage) {
}
