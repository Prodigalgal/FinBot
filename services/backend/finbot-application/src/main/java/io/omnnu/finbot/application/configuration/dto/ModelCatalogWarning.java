package io.omnnu.finbot.application.configuration.dto;

import java.util.Objects;

public record ModelCatalogWarning(
        String code,
        String modelName,
        String message) {
    public ModelCatalogWarning {
        code = requireText(code, "code", 80);
        modelName = normalizeNullable(modelName, 160);
        message = requireText(message, "message", 500);
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }

    private static String normalizeNullable(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("modelName is invalid");
        }
        return normalized;
    }
}
