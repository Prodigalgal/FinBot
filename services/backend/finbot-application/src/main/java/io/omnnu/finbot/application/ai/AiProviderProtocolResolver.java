package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;

@FunctionalInterface
public interface AiProviderProtocolResolver {
    AiProtocol protocolFor(AiProviderProfileId providerProfileId);
}
