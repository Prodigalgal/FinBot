package io.omnnu.finbot.api.identity;

import io.omnnu.finbot.application.identity.CreatedAdminApiToken;
import java.time.Instant;

public record CreatedAdminApiTokenResponse(
        AdminApiTokenResponse token,
        String rawToken) {
    static CreatedAdminApiTokenResponse from(CreatedAdminApiToken created, Instant now) {
        return new CreatedAdminApiTokenResponse(
                AdminApiTokenResponse.from(created.token(), now),
                created.rawToken());
    }
}
