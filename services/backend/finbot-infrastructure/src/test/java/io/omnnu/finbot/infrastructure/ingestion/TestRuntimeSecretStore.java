package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import java.time.Instant;
import java.util.Optional;

class TestRuntimeSecretStore implements RuntimeSecretStore {
    private final String value;

    TestRuntimeSecretStore() {
        this(null);
    }

    TestRuntimeSecretStore(String value) {
        this.value = value;
    }

    @Override
    public Optional<String> resolve(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable) {
        return Optional.ofNullable(value);
    }

    @Override
    public RuntimeSecretStatus status(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RuntimeSecretStatus> put(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String value,
            String fallbackEnvironmentVariable,
            long expectedVersion,
            Instant updatedAt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RuntimeSecretStatus> clear(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable,
            long expectedVersion,
            Instant updatedAt) {
        throw new UnsupportedOperationException();
    }
}
