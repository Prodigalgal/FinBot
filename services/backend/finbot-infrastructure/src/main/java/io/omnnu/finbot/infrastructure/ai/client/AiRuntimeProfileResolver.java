package io.omnnu.finbot.infrastructure.ai.client;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;

public interface AiRuntimeProfileResolver {
    AiRuntimeProfile resolve(AiProviderProfileId profileId);

    default AiRuntimeProfile resolve(AiProviderProfileId profileId, String modelName) {
        return resolve(profileId);
    }
}
