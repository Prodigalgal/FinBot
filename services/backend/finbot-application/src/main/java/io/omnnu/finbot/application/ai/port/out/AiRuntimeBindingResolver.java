package io.omnnu.finbot.application.ai.port.out;

import io.omnnu.finbot.application.ai.dto.AiRuntimeBinding;

import io.omnnu.finbot.domain.configuration.AiModelBinding;

@FunctionalInterface
public interface AiRuntimeBindingResolver {
    AiRuntimeBinding resolve(AiModelBinding binding);
}
