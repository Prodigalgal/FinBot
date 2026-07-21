package io.omnnu.finbot.api.identity;

import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenStatus;
import java.time.Instant;

public record AdminApiTokenResponse(
        String tokenId,
        String displayName,
        String fingerprint,
        String username,
        AdminApiTokenStatus status,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    static AdminApiTokenResponse from(AdminApiToken token, Instant now) {
        return new AdminApiTokenResponse(
                token.tokenId().value(),
                token.displayName(),
                token.fingerprint(),
                token.username(),
                token.statusAt(now),
                token.expiresAt(),
                token.lastUsedAt(),
                token.revokedAt(),
                token.createdAt(),
                token.updatedAt(),
                token.version());
    }
}
