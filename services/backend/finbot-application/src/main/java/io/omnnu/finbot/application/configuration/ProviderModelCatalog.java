package io.omnnu.finbot.application.configuration;

import java.time.Instant;
import java.util.List;

public record ProviderModelCatalog(
        String providerProfileId,
        String status,
        List<String> models,
        Integer httpStatus,
        Long latencyMilliseconds,
        String errorCode,
        String errorMessage,
        Instant checkedAt) {
    public ProviderModelCatalog {
        models = List.copyOf(models);
    }
}
