package io.omnnu.finbot.application.configuration.port.out;

import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderUsage;
import io.omnnu.finbot.application.configuration.dto.SystemSetting;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ConfigurationRepository {
    List<SystemSetting> listSettings();

    List<AiProviderProfile> listProviders();

    List<AiModelProfile> listModels();

    Map<String, AiProviderUsage> providerUsages();

    Optional<AiProviderProfile> createProvider(AiProviderProfile provider, Instant createdAt);

    Optional<AiModelProfile> createModel(AiModelProfile model, Instant createdAt);

    Optional<SystemSetting> updateSetting(String key, String value, long expectedVersion, Instant updatedAt);

    Optional<AiProviderProfile> updateProvider(AiProviderProfile profile, long expectedVersion, Instant updatedAt);

    boolean archiveProvider(String profileId, long expectedVersion, Instant archivedAt);

    Optional<AiModelProfile> updateModel(AiModelProfile profile, long expectedVersion, Instant updatedAt);
}
