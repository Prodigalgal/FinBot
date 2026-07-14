package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.application.ai.AiProviderUnavailableException;

public final class AiProviderConfigurationException extends AiProviderUnavailableException {
    private static final long serialVersionUID = 1L;

    public AiProviderConfigurationException(String message) {
        super(message);
    }
}
