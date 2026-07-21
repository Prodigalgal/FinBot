package io.omnnu.finbot.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record AdminApiToken(
        AdminApiTokenId tokenId,
        String displayName,
        String fingerprint,
        String username,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{16}");

    public AdminApiToken {
        Objects.requireNonNull(tokenId, "tokenId");
        displayName = required(displayName, "displayName", 120);
        fingerprint = required(fingerprint, "fingerprint", 16);
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint is invalid");
        }
        username = required(username, "username", 80);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (lastUsedAt != null && lastUsedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastUsedAt must not be before createdAt");
        }
        if (revokedAt != null && revokedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("revokedAt must not be before createdAt");
        }
        if (updatedAt.isBefore(createdAt)
                || lastUsedAt != null && updatedAt.isBefore(lastUsedAt)
                || revokedAt != null && updatedAt.isBefore(revokedAt)) {
            throw new IllegalArgumentException("updatedAt is inconsistent");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public AdminApiTokenStatus statusAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (revokedAt != null) {
            return AdminApiTokenStatus.REVOKED;
        }
        return expiresAt != null && !expiresAt.isAfter(now)
                ? AdminApiTokenStatus.EXPIRED
                : AdminApiTokenStatus.ACTIVE;
    }

    private static String required(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
