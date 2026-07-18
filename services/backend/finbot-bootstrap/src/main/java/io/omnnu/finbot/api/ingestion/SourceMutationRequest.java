package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.application.ingestion.SourceDefinition;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

public record SourceMutationRequest(
        @NotBlank @Size(max = 160) String displayName,
        @NotNull SourceMode mode,
        @NotNull SourceTier tier,
        @NotBlank @Size(max = 80) String category,
        @Size(max = 80) String provider,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal trustWeight,
        @Min(10) @Max(2_592_000) int pollIntervalSeconds,
        @NotNull SourcePriority priority,
        @NotNull @Size(max = 64) List<@NotBlank @Size(max = 32) String> assetScope,
        @NotNull @Size(max = 32) List<@NotNull @Valid URI> feedUrls,
        @NotNull @Size(max = 32) List<@NotNull @Valid URI> seedUrls,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 1_000) String> searchQueries,
        URI endpointBaseUrl,
        boolean credentialSupported,
        OutboundRoute outboundRoute,
        @Min(1) @Max(100) int maximumResults,
        @Min(0) @Max(20) int maximumScrapeTargets,
        boolean enabled,
        @Valid AiWebSearchBindingRequest aiWebSearchBinding) {
    SourceDefinition toDefinition() {
        return new SourceDefinition(
                displayName,
                mode,
                tier,
                category,
                provider,
                trustWeight,
                pollIntervalSeconds,
                priority,
                assetScope,
                feedUrls,
                seedUrls,
                searchQueries,
                endpointBaseUrl,
                credentialSupported,
                outboundRoute,
                maximumResults,
                maximumScrapeTargets,
                enabled,
                aiWebSearchBinding == null ? null : aiWebSearchBinding.toDomain());
    }
}
