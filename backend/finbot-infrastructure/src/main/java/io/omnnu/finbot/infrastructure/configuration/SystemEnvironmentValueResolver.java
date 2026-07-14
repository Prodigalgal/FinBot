package io.omnnu.finbot.infrastructure.configuration;

import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class SystemEnvironmentValueResolver implements EnvironmentValueResolver {
    @Override
    public Optional<String> resolve(String environmentVariable) {
        if (environmentVariable == null || environmentVariable.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(System.getenv(environmentVariable))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }
}
