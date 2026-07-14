package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.domain.identity.AdminSessionId;
import java.time.Instant;
import java.util.Objects;

public record AdminSession(
        AdminSessionId sessionId,
        String username,
        Instant expiresAt,
        Instant lastSeenAt,
        Instant revokedAt,
        Instant createdAt) {
    public AdminSession {
        Objects.requireNonNull(sessionId, "sessionId");
        username = Objects.requireNonNull(username, "username").strip();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean activeAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }
}
