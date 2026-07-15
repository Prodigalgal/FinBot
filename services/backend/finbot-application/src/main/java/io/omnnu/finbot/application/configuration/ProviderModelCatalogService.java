package io.omnnu.finbot.application.configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class ProviderModelCatalogService implements ProviderModelCatalogUseCase {
    private final ConfigurationUseCase configuration;
    private final EnvironmentValueResolver environment;
    private final ProviderModelCatalogGateway gateway;

    public ProviderModelCatalogService(
            ConfigurationUseCase configuration,
            EnvironmentValueResolver environment,
            ProviderModelCatalogGateway gateway) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public ProviderModelCatalog probe(String providerProfileId) {
        var provider = configuration.snapshot().providers().stream()
                .filter(value -> value.profileId().equals(providerProfileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI provider profile"));
        if (!provider.enabled()) {
            throw new IllegalArgumentException("AI provider is disabled");
        }
        if (!provider.baseUrlConfigured() || !provider.apiKeyConfigured()) {
            throw new IllegalArgumentException("AI provider base URL or API key is not configured");
        }
        var apiKey = environment.resolve(provider.apiKeyEnv())
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("AI provider API key is not configured"));
        return gateway.probe(
                provider.profileId(),
                URI.create(provider.baseUrl()),
                apiKey,
                Duration.ofSeconds(provider.requestTimeoutSeconds()));
    }
}
