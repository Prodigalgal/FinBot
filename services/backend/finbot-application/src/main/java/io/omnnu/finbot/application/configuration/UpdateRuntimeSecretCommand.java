package io.omnnu.finbot.application.configuration;

public record UpdateRuntimeSecretCommand(
        RuntimeSecretScope scope,
        String targetId,
        String secretName,
        String value,
        long expectedVersion) {
}
