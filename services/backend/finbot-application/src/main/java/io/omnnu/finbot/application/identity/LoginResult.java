package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.domain.identity.AdminSessionId;
import java.time.Instant;

public record LoginResult(
        AdminSessionId sessionId,
        String rawSessionToken,
        String username,
        Instant expiresAt) {
}
