package io.omnnu.finbot.application.configuration;

import java.util.Optional;

public interface EnvironmentValueResolver {
    Optional<String> resolve(String environmentVariable);
}
