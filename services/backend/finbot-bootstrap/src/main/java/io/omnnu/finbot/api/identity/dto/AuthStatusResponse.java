package io.omnnu.finbot.api.identity.dto;

import java.time.Instant;

public record AuthStatusResponse(
        boolean authenticated,
        String username,
        Instant expiresAt,
        String csrfToken) {
    public static AuthStatusResponse anonymous(String csrfToken) {
        return new AuthStatusResponse(false, null, null, csrfToken);
    }
}
