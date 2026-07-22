package io.omnnu.finbot.api.identity.dto;

import io.omnnu.finbot.application.identity.dto.CreatedAdminApiToken;
import java.time.Instant;

public record CreatedAdminApiTokenResponse(
        AdminApiTokenResponse token,
        String rawToken) {
    public static CreatedAdminApiTokenResponse from(CreatedAdminApiToken created, Instant now) {
        return new CreatedAdminApiTokenResponse(
                AdminApiTokenResponse.from(created.token(), now),
                created.rawToken());
    }
}
