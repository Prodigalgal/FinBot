package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

public record SourceDefinition(
        String displayName,
        SourceMode mode,
        SourceTier tier,
        String category,
        String provider,
        BigDecimal trustWeight,
        int pollIntervalSeconds,
        SourcePriority priority,
        List<String> assetScope,
        List<URI> feedUrls,
        List<URI> seedUrls,
        List<String> searchQueries,
        URI endpointBaseUrl,
        boolean credentialSupported,
        OutboundRoute outboundRoute,
        int maximumResults,
        int maximumScrapeTargets,
        boolean enabled) {
}
