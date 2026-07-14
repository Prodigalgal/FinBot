package io.omnnu.finbot.application.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConfigurationRepository {
    List<SystemSetting> listSettings();

    List<AiProviderProfile> listProviders();

    List<AiModelProfile> listModels();

    Optional<SystemSetting> updateSetting(String key, String value, long expectedVersion, Instant updatedAt);

    Optional<AiProviderProfile> updateProvider(AiProviderProfile profile, long expectedVersion, Instant updatedAt);

    Optional<AiModelProfile> updateModel(AiModelProfile profile, long expectedVersion, Instant updatedAt);
}
