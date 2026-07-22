package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;

interface AiRuntimeProfileResolver {
    AiRuntimeProfile resolve(AiProviderProfileId profileId);
}
