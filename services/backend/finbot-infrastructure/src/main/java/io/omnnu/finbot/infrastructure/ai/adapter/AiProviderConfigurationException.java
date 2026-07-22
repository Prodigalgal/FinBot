package io.omnnu.finbot.infrastructure.ai.adapter;

import io.omnnu.finbot.application.ai.exception.AiProviderUnavailableException;

public final class AiProviderConfigurationException extends AiProviderUnavailableException {
    private static final long serialVersionUID = 1L;

    public AiProviderConfigurationException(String message) {
        super(message);
    }
}
