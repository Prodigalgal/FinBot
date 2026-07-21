package io.omnnu.finbot.application.identity;

public record CreateAdminApiTokenCommand(
        String displayName,
        Integer expiresInDays,
        String username) {
}
