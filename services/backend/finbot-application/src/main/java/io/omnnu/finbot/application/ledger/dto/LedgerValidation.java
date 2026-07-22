package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

final class LedgerValidation {
    private LedgerValidation() {
    }

    static String required(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }

    static String optional(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, fieldName, maximumLength);
    }

    static String currency(String value) {
        return required(value, "currency", 16).toUpperCase(Locale.ROOT);
    }

    static BigDecimal positive(BigDecimal value, String fieldName) {
        return DecimalValue.positive(value, fieldName);
    }

    static BigDecimal nonNegative(BigDecimal value, String fieldName) {
        return DecimalValue.nonNegative(value, fieldName);
    }

    static BigDecimal optionalPositive(BigDecimal value, String fieldName) {
        return value == null ? null : positive(value, fieldName);
    }

    static void requireTimeline(Instant occurredAt, Instant receivedAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (receivedAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("receivedAt must not be before occurredAt");
        }
    }
}
