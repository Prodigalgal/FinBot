package io.omnnu.finbot.application.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.configuration.SettingSource;
import io.omnnu.finbot.domain.configuration.SettingType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigurationApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @Test
    void permanentlyRejectsLiveTradingAndFirecrawlDirectFallback() {
        var repository = new FakeConfigurationRepository(List.of(
                setting("execution.live.enabled", SettingType.BOOLEAN, "false"),
                setting("ingestion.firecrawl.proxy_required", SettingType.BOOLEAN, "true")));
        var service = service(repository);

        assertThrows(IllegalArgumentException.class, () -> service.updateSetting(
                new UpdateSettingCommand("execution.live.enabled", "true", 0)));
        assertThrows(IllegalArgumentException.class, () -> service.updateSetting(
                new UpdateSettingCommand("ingestion.firecrawl.proxy_required", "false", 0)));
    }

    @Test
    void normalizesTypedValueAndUsesOptimisticVersion() {
        var repository = new FakeConfigurationRepository(List.of(
                setting("autonomous.interval", SettingType.DURATION, "PT1H")));
        var service = service(repository);

        var updated = service.updateSetting(new UpdateSettingCommand(
                "autonomous.interval", "pt30m", 0));

        assertEquals("PT30M", updated.value());
        assertEquals(1, updated.version());
        assertEquals(SettingSource.USER, updated.source());
    }

    @Test
    void createsVendorAgnosticProviderCredentialBinding() {
        var repository = new FakeConfigurationRepository(List.of());
        var service = service(repository);

        var created = service.createProvider(new CreateProviderCommand(
                "任意兼容厂商",
                AiProtocol.RESPONSES,
                ReasoningParameterStyle.NESTED,
                "https://provider.example/v1",
                true,
                10,
                1800,
                5,
                3600));

        assertEquals("provider_test_generated_id", created.profileId());
        assertEquals(5, created.maximumConcurrentRequests());
        assertEquals(3600, created.acquireTimeoutSeconds());
        assertEquals(
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                repository.providers.getFirst().apiKeyEnv());
        assertTrue(repository.models.isEmpty());
    }

    @Test
    void refusesToDeleteReferencedProvider() {
        var repository = new FakeConfigurationRepository(List.of());
        var service = service(repository);
        var created = service.createProvider(new CreateProviderCommand(
                "被引用厂商",
                AiProtocol.CHAT,
                ReasoningParameterStyle.FLAT,
                "https://provider.example/v1",
                true,
                10,
                120,
                5,
                1800));
        repository.usages = Map.of(created.profileId(), new AiProviderUsage(1, 0, 0));

        assertThrows(ConfigurationConflictException.class, () -> service.deleteProvider(
                new DeleteProviderCommand(created.profileId(), created.version())));
        assertTrue(repository.providers.stream()
                .anyMatch(provider -> provider.profileId().equals(created.profileId())));
    }

    @Test
    void probesUnsavedProviderAndReturnsDiscoveredModelsWithoutPersistingIt() {
        var repository = new FakeConfigurationRepository(List.of());
        var service = new ProviderModelCatalogService(
                repository,
                ignored -> Optional.empty(),
                new EmptyRuntimeSecretStore(),
                (providerProfileId, baseUri, apiKey, timeout) -> {
                    assertEquals("draft", providerProfileId);
                    assertEquals("https://provider.example/v1", baseUri.toString());
                    assertEquals("test-api-key", apiKey);
                    assertEquals(1800, timeout.toSeconds());
                    return new ProviderModelCatalog(
                            providerProfileId,
                            "READY",
                            List.of("model-a", "model-b"),
                            200,
                            25L,
                            null,
                            null,
                            NOW);
                });

        var result = service.probe(new ProbeProviderCommand(
                "https://provider.example/v1",
                "test-api-key",
                1800));

        assertEquals(List.of("model-a", "model-b"), result.models());
        assertTrue(repository.providers.isEmpty());
        assertTrue(repository.models.isEmpty());
    }

    @Test
    void rejectsUnsafeUnsavedProviderProbeEndpoint() {
        var repository = new FakeConfigurationRepository(List.of());
        ProviderModelCatalogGateway gateway = (providerProfileId, baseUri, apiKey, timeout) -> {
            throw new AssertionError("Unsafe endpoint must not reach the gateway");
        };
        var service = new ProviderModelCatalogService(
                repository,
                ignored -> Optional.empty(),
                new EmptyRuntimeSecretStore(),
                gateway);

        assertThrows(IllegalArgumentException.class, () -> service.probe(
                new ProbeProviderCommand("file:///tmp/models", "test-api-key", 30)));
    }

    private static ConfigurationApplicationService service(ConfigurationRepository repository) {
        EnvironmentValueResolver environment = ignored -> Optional.empty();
        return new ConfigurationApplicationService(
                repository,
                environment,
                new EmptyRuntimeSecretStore(),
                prefix -> prefix + "test_generated_id",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SystemSetting setting(String key, SettingType type, String value) {
        return new SystemSetting(key, type, value, SettingSource.DEFAULT, "test", 0, NOW);
    }

    private static final class FakeConfigurationRepository implements ConfigurationRepository {
        private final List<SystemSetting> settings;
        private final List<AiProviderProfile> providers = new ArrayList<>();
        private final List<AiModelProfile> models = new ArrayList<>();
        private Map<String, AiProviderUsage> usages = Map.of();

        private FakeConfigurationRepository(List<SystemSetting> settings) {
            this.settings = new ArrayList<>(settings);
        }

        @Override
        public List<SystemSetting> listSettings() {
            return List.copyOf(settings);
        }

        @Override
        public List<AiProviderProfile> listProviders() {
            return List.copyOf(providers);
        }

        @Override
        public List<AiModelProfile> listModels() {
            return List.copyOf(models);
        }

        @Override
        public Map<String, AiProviderUsage> providerUsages() {
            return usages;
        }

        @Override
        public Optional<AiProviderProfile> createProvider(
                AiProviderProfile provider,
                Instant createdAt) {
            providers.add(provider);
            return Optional.of(provider);
        }

        @Override
        public Optional<AiModelProfile> createModel(AiModelProfile model, Instant createdAt) {
            models.add(model);
            return Optional.of(model);
        }

        @Override
        public Optional<SystemSetting> updateSetting(
                String key,
                String value,
                long expectedVersion,
                Instant updatedAt) {
            for (var index = 0; index < settings.size(); index++) {
                var current = settings.get(index);
                if (current.key().equals(key) && current.version() == expectedVersion) {
                    var updated = new SystemSetting(
                            current.key(), current.type(), value, SettingSource.USER,
                            current.description(), current.version() + 1, updatedAt);
                    settings.set(index, updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<AiProviderProfile> updateProvider(
                AiProviderProfile profile,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }

        @Override
        public boolean archiveProvider(String profileId, long expectedVersion, Instant archivedAt) {
            return providers.removeIf(provider -> provider.profileId().equals(profileId)
                    && provider.version() == expectedVersion);
        }

        @Override
        public Optional<AiModelProfile> updateModel(
                AiModelProfile profile,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }
    }

    private static final class EmptyRuntimeSecretStore implements RuntimeSecretStore {
        @Override
        public Optional<String> resolve(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return Optional.empty();
        }

        @Override
        public RuntimeSecretStatus status(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return new RuntimeSecretStatus(
                    scope,
                    targetId,
                    secretName,
                    RuntimeSecretSource.UNCONFIGURED,
                    false,
                    null,
                    0,
                    null);
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
            return Optional.empty();
        }

        @Override
        public Optional<RuntimeSecretStatus> clear(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }
    }
}
