package io.omnnu.finbot.application.configuration.dto;

public record ClearRuntimeSecretCommand(
        RuntimeSecretScope scope,
        String targetId,
        String secretName,
        long expectedVersion) {
}
