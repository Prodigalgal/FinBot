package io.omnnu.finbot.application.configuration;

public record ClearRuntimeSecretCommand(
        RuntimeSecretScope scope,
        String targetId,
        String secretName,
        long expectedVersion) {
}
