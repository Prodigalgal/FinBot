package io.omnnu.finbot.application.configuration.port.out;

import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretTarget;

import java.util.Optional;

public interface RuntimeSecretTargetRepository {
    Optional<RuntimeSecretTarget> find(RuntimeSecretScope scope, String targetId, String secretName);
}
