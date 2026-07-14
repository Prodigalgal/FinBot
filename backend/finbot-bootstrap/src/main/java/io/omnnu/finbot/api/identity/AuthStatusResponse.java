package io.omnnu.finbot.api.identity;

import java.time.Instant;

public record AuthStatusResponse(
        boolean authenticated,
        String username,
        Instant expiresAt,
        String csrfToken) {
    static AuthStatusResponse anonymous(String csrfToken) {
        return new AuthStatusResponse(false, null, null, csrfToken);
    }
}
