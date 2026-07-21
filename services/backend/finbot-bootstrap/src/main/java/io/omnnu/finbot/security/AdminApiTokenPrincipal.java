package io.omnnu.finbot.security;

import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;

public record AdminApiTokenPrincipal(
        AdminApiTokenId tokenId,
        String username,
        Instant expiresAt) implements Principal {
    public AdminApiTokenPrincipal {
        Objects.requireNonNull(tokenId, "tokenId");
        username = Objects.requireNonNull(username, "username").strip();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
    }

    @Override
    public String getName() {
        return username;
    }
}
