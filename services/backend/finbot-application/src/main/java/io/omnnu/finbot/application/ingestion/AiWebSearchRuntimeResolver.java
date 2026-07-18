package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;

@FunctionalInterface
public interface AiWebSearchRuntimeResolver {
    AiWebSearchRuntimeProfile resolve(AiWebSearchBinding binding);
}
