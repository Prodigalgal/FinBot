package io.omnnu.finbot.application.configuration.dto;

import java.time.Instant;
import java.util.List;

public record ProviderModelCatalog(
        String providerProfileId,
        String status,
        List<String> models,
        List<DiscoveredModelCapability> modelCapabilities,
        List<ModelCatalogWarning> warnings,
        Integer httpStatus,
        Long latencyMilliseconds,
        String errorCode,
        String errorMessage,
        Instant checkedAt) {
    public ProviderModelCatalog {
        models = List.copyOf(models);
        modelCapabilities = List.copyOf(modelCapabilities);
        warnings = List.copyOf(warnings);
    }

    public ProviderModelCatalog(
            String providerProfileId,
            String status,
            List<String> models,
            Integer httpStatus,
            Long latencyMilliseconds,
            String errorCode,
            String errorMessage,
            Instant checkedAt) {
        this(
                providerProfileId,
                status,
                models,
                models.stream().map(DiscoveredModelCapability::unknown).toList(),
                List.of(),
                httpStatus,
                latencyMilliseconds,
                errorCode,
                errorMessage,
                checkedAt);
    }
}
