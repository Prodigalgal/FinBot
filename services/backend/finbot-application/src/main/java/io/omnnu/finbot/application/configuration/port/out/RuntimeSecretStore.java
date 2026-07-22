package io.omnnu.finbot.application.configuration.port.out;

import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretStatus;

import java.time.Instant;
import java.util.Optional;

public interface RuntimeSecretStore {
    Optional<String> resolve(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable);

    RuntimeSecretStatus status(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable);

    Optional<RuntimeSecretStatus> put(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String value,
            String fallbackEnvironmentVariable,
            long expectedVersion,
            Instant updatedAt);

    Optional<RuntimeSecretStatus> clear(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable,
            long expectedVersion,
            Instant updatedAt);
}
