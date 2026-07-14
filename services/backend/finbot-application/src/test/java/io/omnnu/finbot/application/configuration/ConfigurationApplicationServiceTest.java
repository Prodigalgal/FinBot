package io.omnnu.finbot.application.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.configuration.SettingSource;
import io.omnnu.finbot.domain.configuration.SettingType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

    private static ConfigurationApplicationService service(ConfigurationRepository repository) {
        EnvironmentValueResolver environment = ignored -> Optional.empty();
        return new ConfigurationApplicationService(
                repository,
                environment,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SystemSetting setting(String key, SettingType type, String value) {
        return new SystemSetting(key, type, value, SettingSource.DEFAULT, "test", 0, NOW);
    }

    private static final class FakeConfigurationRepository implements ConfigurationRepository {
        private final List<SystemSetting> settings;

        private FakeConfigurationRepository(List<SystemSetting> settings) {
            this.settings = new ArrayList<>(settings);
        }

        @Override
        public List<SystemSetting> listSettings() {
            return List.copyOf(settings);
        }

        @Override
        public List<AiProviderProfile> listProviders() {
            return List.of();
        }

        @Override
        public List<AiModelProfile> listModels() {
            return List.of();
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
        public Optional<AiModelProfile> updateModel(
                AiModelProfile profile,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }
    }
}
