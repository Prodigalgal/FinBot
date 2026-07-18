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
        if (mode == SourceMode.HTML_DOCUMENT && outboundRoute != OutboundRoute.WEB_CRAWL) {
            throw new IllegalArgumentException("HTML sources must use the WEB_CRAWL route");
        }
        if (mode == SourceMode.SEARCH_DISCOVERY && outboundRoute != OutboundRoute.WEB_CRAWL) {
            throw new IllegalArgumentException("Search discovery sources must use the WEB_CRAWL route");
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
        var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(fieldName + " contains an invalid HTTP URL");
        }
        if (isBlockedHost(host)) {
            throw new IllegalArgumentException(fieldName + " contains a blocked private or local host");
        }
    }

    private static boolean isBlockedHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        var normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if (normalized.contains(":")) {
            var value = normalized.replace(":", "");
            return normalized.equals("::1")
                    || value.startsWith("fc")
                    || value.startsWith("fd")
                    || value.startsWith("fe8")
                    || value.startsWith("fe9")
                    || value.startsWith("fea")
                    || value.startsWith("feb")
                    || value.startsWith("::ffff:127");
        }
        var octets = normalized.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            var first = Integer.parseInt(octets[0]);
            var second = Integer.parseInt(octets[1]);
            var address = Integer.parseInt(octets[2]);
            var last = Integer.parseInt(octets[3]);
            if (first < 0 || first > 255 || second < 0 || second > 255
                    || address < 0 || address > 255 || last < 0 || last > 255) {
                return true;
            }
            return first == 0 || first == 10 || first == 127 || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127
                    || first == 198 && (second == 18 || second == 19)
                    || first >= 224;
        } catch (NumberFormatException exception) {
            return false;
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
