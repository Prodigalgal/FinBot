package io.omnnu.finbot.domain.shared;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DomainText {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_-]{7,79}");
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9_-]{2,48}");

    private DomainText() {
    }

    public static String identifier(String value, String prefix) {
        var normalized = Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(prefix) || !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid " + prefix + " identifier: " + normalized);
        }
        return normalized;
    }

    public static String symbol(String value) {
        var normalized = Objects.requireNonNull(value, "value").strip().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid instrument symbol: " + normalized);
        }
        return normalized;
    }

    public static String required(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " must contain 1 to " + maximumLength + " characters");
        }
        return normalized;
    }
}
