package io.omnnu.finbot.application.exchange;

public record ExchangeSubmissionResult(
        ExchangeSubmissionStatus status,
        String exchangeOrderId,
        Integer httpStatus,
        String responseJson,
        String errorCode,
        String safeMessage) {
}
