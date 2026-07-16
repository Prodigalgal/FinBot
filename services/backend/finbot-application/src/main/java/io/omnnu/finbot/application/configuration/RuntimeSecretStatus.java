package io.omnnu.finbot.application.configuration;

import java.time.Instant;
import java.util.Objects;

public record RuntimeSecretStatus(
        RuntimeSecretScope scope,
        String targetId,
        String secretName,
        RuntimeSecretSource source,
        boolean configured,
        String fingerprint,
        long version,
        Instant updatedAt) {
    public RuntimeSecretStatus {
        Objects.requireNonNull(scope, "scope");
        targetId = requireText(targetId, "targetId", 80);
        secretName = requireText(secretName, "secretName", 40);
        Objects.requireNonNull(source, "source");
        fingerprint = normalize(fingerprint);
        if (configured != (source != RuntimeSecretSource.UNCONFIGURED)) {
            throw new IllegalArgumentException("configured must match secret source");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field, int maximumLength) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
