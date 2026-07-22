package io.omnnu.finbot.application.identity.dto;

public record CreateAdminApiTokenCommand(
        String displayName,
        Integer expiresInDays,
        String username) {
}
