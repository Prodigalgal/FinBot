package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.util.List;

public record SourceResponse(
        String sourceId,
        String displayName,
        String mode,
        String tier,
        String category,
        String provider,
        java.math.BigDecimal trustWeight,
        int pollIntervalSeconds,
        String priority,
        List<String> assetScope,
        List<String> feedUrls,
        List<String> seedUrls,
        List<String> searchQueries,
        String endpointBaseUrl,
        boolean credentialSupported,
        String outboundRoute,
        int maximumResults,
        int maximumScrapeTargets,
        boolean enabled,
        long version,
        AiWebSearchBindingResponse aiWebSearchBinding) {
    static SourceResponse from(InformationSource source) {
        return new SourceResponse(
                source.sourceId().value(),
                source.displayName(),
                source.mode().name(),
                source.tier().name(),
                source.category(),
                source.provider(),
                source.trustWeight(),
                source.pollIntervalSeconds(),
                source.priority().name(),
                source.assetScope(),
                source.feedUrls().stream().map(Object::toString).toList(),
                source.seedUrls().stream().map(Object::toString).toList(),
                source.searchQueries(),
                source.endpointBaseUrl() == null ? null : source.endpointBaseUrl().toString(),
                source.credentialEnvironmentVariable() != null,
                source.outboundRoute() == null ? null : source.outboundRoute().name(),
                source.maximumResults(),
                source.maximumScrapeTargets(),
                source.enabled(),
                source.version(),
                AiWebSearchBindingResponse.from(source.aiWebSearchBinding()));
    }
}
