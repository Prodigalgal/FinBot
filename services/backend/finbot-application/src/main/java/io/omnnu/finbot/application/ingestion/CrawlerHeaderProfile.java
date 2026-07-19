package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CrawlerHeaderProfile(
        CrawlerHeaderProfileId profileId,
        String displayName,
        String userAgent,
        String accept,
        String acceptLanguage,
        Map<String, String> additionalHeaders,
        boolean enabled,
        long usageCount,
        long version,
        Instant updatedAt) {
    public CrawlerHeaderProfile {
        profileId = Objects.requireNonNull(profileId, "profileId");
        displayName = CrawlerHeaderRules.displayName(displayName);
        userAgent = CrawlerHeaderRules.userAgent(userAgent);
        accept = CrawlerHeaderRules.optionalAccept(accept);
        acceptLanguage = CrawlerHeaderRules.optionalAcceptLanguage(acceptLanguage);
        additionalHeaders = CrawlerHeaderRules.additionalHeaders(additionalHeaders);
        if (usageCount < 0 || version < 0) {
            throw new IllegalArgumentException("Crawler header profile counters are invalid");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
