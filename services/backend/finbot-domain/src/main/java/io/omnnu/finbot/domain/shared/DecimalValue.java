package io.omnnu.finbot.domain.shared;

import java.math.BigDecimal;
import java.util.Objects;

public final class DecimalValue {
    private DecimalValue() {
    }

    public static BigDecimal finite(BigDecimal value, String fieldName) {
        return Objects.requireNonNull(value, fieldName).stripTrailingZeros();
    }

    public static BigDecimal positive(BigDecimal value, String fieldName) {
        var normalized = finite(value, fieldName);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }

    public static BigDecimal nonNegative(BigDecimal value, String fieldName) {
        var normalized = finite(value, fieldName);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return normalized;
    }
}
