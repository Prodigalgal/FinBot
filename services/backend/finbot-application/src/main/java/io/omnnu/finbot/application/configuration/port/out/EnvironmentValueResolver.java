package io.omnnu.finbot.application.configuration.port.out;

import java.util.Optional;

public interface EnvironmentValueResolver {
    Optional<String> resolve(String environmentVariable);
}
