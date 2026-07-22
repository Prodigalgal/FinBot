package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.AiWebSearchRuntimeProfile;

import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;

@FunctionalInterface
public interface AiWebSearchRuntimeResolver {
    AiWebSearchRuntimeProfile resolve(AiWebSearchBinding binding);
}
