package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.configuration.AiModelBinding;

@FunctionalInterface
public interface AiRuntimeBindingResolver {
    AiRuntimeBinding resolve(AiModelBinding binding);
}
