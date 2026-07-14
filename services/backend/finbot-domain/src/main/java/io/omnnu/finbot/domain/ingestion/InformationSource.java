package io.omnnu.finbot.domain.ingestion;

import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Objects;

public record InformationSource(
        SourceId sourceId,
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
        String credentialEnvironmentVariable,
        OutboundRoute outboundRoute,
        int maximumResults,
        int maximumScrapeTargets,
        boolean enabled,
        long version) {
    public InformationSource {
        Objects.requireNonNull(sourceId, "sourceId");
        displayName = DomainText.required(displayName, "displayName", 160);
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(tier, "tier");
        category = DomainText.required(category, "category", 80);
        provider = optional(provider, 80);
        trustWeight = DecimalValue.nonNegative(trustWeight, "trustWeight");
        if (trustWeight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("trustWeight must not exceed one");
        }
        if (pollIntervalSeconds < 10 || pollIntervalSeconds > 2_592_000) {
            throw new IllegalArgumentException("pollIntervalSeconds is outside the supported range");
        }
        Objects.requireNonNull(priority, "priority");
        assetScope = cleanStrings(assetScope, 64, 32, "assetScope");
        feedUrls = secureUris(feedUrls, "feedUrls");
        seedUrls = secureUris(seedUrls, "seedUrls");
        searchQueries = cleanStrings(searchQueries, 32, 1_000, "searchQueries");
        if (endpointBaseUrl != null) {
            requireHttpUri(endpointBaseUrl, "endpointBaseUrl");
        }
        credentialEnvironmentVariable = optional(credentialEnvironmentVariable, 120);
        if (mode.firecrawl() && outboundRoute != OutboundRoute.FIRECRAWL) {
            throw new IllegalArgumentException("Firecrawl sources must use the FIRECRAWL route");
        }
        if (maximumResults < 1 || maximumResults > 100
                || maximumScrapeTargets < 0 || maximumScrapeTargets > 20) {
            throw new IllegalArgumentException("Source result limits are invalid");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public String defaultQuery(String requestedQuery) {
        var requested = requestedQuery == null ? "" : requestedQuery.strip();
        if (searchQueries.isEmpty()) {
            return requested.isEmpty() ? displayName : requested;
        }
        if (requested.isEmpty()) {
            return searchQueries.getFirst();
        }
        return searchQueries.getFirst() + "；研究焦点：" + requested;
    }

    private static List<URI> secureUris(List<URI> values, String fieldName) {
        var copy = List.copyOf(values);
        if (copy.size() > 32) {
            throw new IllegalArgumentException(fieldName + " contains too many URLs");
        }
        copy.forEach(uri -> requireHttpUri(uri, fieldName));
        return copy;
    }

    private static void requireHttpUri(URI uri, String fieldName) {
        Objects.requireNonNull(uri, fieldName);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(fieldName + " contains an invalid HTTP URL");
        }
    }

    private static List<String> cleanStrings(
            List<String> values,
            int maximumItems,
            int maximumLength,
            String fieldName) {
        var copy = values.stream().map(value -> Objects.requireNonNull(value, fieldName).strip()).toList();
        if (copy.size() > maximumItems
                || copy.stream().anyMatch(value -> value.isEmpty() || value.length() > maximumLength)) {
            throw new IllegalArgumentException(fieldName + " contains invalid values");
        }
        return List.copyOf(copy);
    }

    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Optional source value is too long");
        }
        return normalized;
    }
}
