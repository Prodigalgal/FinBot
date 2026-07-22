package io.omnnu.finbot.application.configuration.dto;

import io.omnnu.finbot.domain.configuration.SettingSource;
import io.omnnu.finbot.domain.configuration.SettingType;
import java.time.Instant;
import java.util.Objects;

public record SystemSetting(
        String key,
        SettingType type,
        String value,
        SettingSource source,
        String description,
        long version,
        Instant updatedAt) {
    public SystemSetting {
        key = requireText(key, "key", 120);
        Objects.requireNonNull(type, "type");
        value = Objects.requireNonNull(value, "value").strip();
        Objects.requireNonNull(source, "source");
        description = requireText(description, "description", 500);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
