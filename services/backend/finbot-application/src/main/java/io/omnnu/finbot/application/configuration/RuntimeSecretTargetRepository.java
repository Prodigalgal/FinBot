package io.omnnu.finbot.application.configuration;

import java.util.Optional;

public interface RuntimeSecretTargetRepository {
    Optional<RuntimeSecretTarget> find(RuntimeSecretScope scope, String targetId, String secretName);
}
