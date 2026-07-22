package io.omnnu.finbot.application.configuration.service;

import io.omnnu.finbot.application.configuration.dto.ProbeProviderCommand;
import io.omnnu.finbot.application.configuration.dto.ProviderModelCatalog;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.port.in.ProviderModelCatalogUseCase;
import io.omnnu.finbot.application.configuration.port.out.ConfigurationRepository;
import io.omnnu.finbot.application.configuration.port.out.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.port.out.ProviderModelCatalogGateway;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.application.configuration.validation.PublicAiProviderEndpointPolicy;

import java.time.Duration;
import java.util.Objects;

public final class ProviderModelCatalogService implements ProviderModelCatalogUseCase {
    private final ConfigurationRepository configuration;
    private final EnvironmentValueResolver environment;
    private final RuntimeSecretStore runtimeSecrets;
    private final ProviderModelCatalogGateway gateway;

    public ProviderModelCatalogService(
            ConfigurationRepository configuration,
            EnvironmentValueResolver environment,
            RuntimeSecretStore runtimeSecrets,
            ProviderModelCatalogGateway gateway) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public ProviderModelCatalog probe(String providerProfileId) {
        var provider = configuration.listProviders().stream()
                .filter(value -> value.profileId().equals(providerProfileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI provider profile"));
        if (!provider.enabled()) {
            throw new IllegalArgumentException("AI provider is disabled");
        }
        var baseUrl = provider.baseUrl() == null
                ? environment.resolve(provider.baseUrlEnv()).orElse(null)
                : provider.baseUrl();
        var apiKey = runtimeSecrets.resolve(
                        RuntimeSecretScope.AI_PROVIDER,
                        provider.profileId(),
                        "API_KEY",
                        provider.apiKeyEnv())
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("AI provider API key is not configured"));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("AI provider base URL is not configured");
        }
        return gateway.probe(
                provider.profileId(),
                PublicAiProviderEndpointPolicy.parse(baseUrl),
                apiKey,
                Duration.ofSeconds(provider.requestTimeoutSeconds()));
    }

    @Override
    public ProviderModelCatalog probe(ProbeProviderCommand command) {
        Objects.requireNonNull(command, "command");
        var baseUrl = Objects.requireNonNull(command.baseUrl(), "baseUrl").strip();
        var apiKey = Objects.requireNonNull(command.apiKey(), "apiKey").strip();
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("AI provider API key is required");
        }
        if (command.requestTimeoutSeconds() < 5 || command.requestTimeoutSeconds() > 3600) {
            throw new IllegalArgumentException("AI provider request timeout is out of range");
        }
        var baseUri = PublicAiProviderEndpointPolicy.parse(baseUrl);
        return gateway.probe(
                "draft",
                baseUri,
                apiKey,
                Duration.ofSeconds(command.requestTimeoutSeconds()));
    }
}
