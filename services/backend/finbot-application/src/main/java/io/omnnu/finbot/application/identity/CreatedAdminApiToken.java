package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.domain.identity.AdminApiToken;
import java.util.Objects;

public record CreatedAdminApiToken(
        AdminApiToken token,
        String rawToken) {
    public CreatedAdminApiToken {
        Objects.requireNonNull(token, "token");
        rawToken = Objects.requireNonNull(rawToken, "rawToken");
    }
}
