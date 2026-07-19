package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import java.time.Instant;
import java.util.Map;

public record CrawlerHeaderProfileResponse(
        String profileId,
        String displayName,
        String userAgent,
        String accept,
        String acceptLanguage,
        Map<String, String> additionalHeaders,
        boolean enabled,
        long usageCount,
        long version,
        Instant updatedAt) {
    static CrawlerHeaderProfileResponse from(CrawlerHeaderProfile profile) {
        return new CrawlerHeaderProfileResponse(
                profile.profileId().value(),
                profile.displayName(),
                profile.userAgent(),
                profile.accept(),
                profile.acceptLanguage(),
                profile.additionalHeaders(),
                profile.enabled(),
                profile.usageCount(),
                profile.version(),
                profile.updatedAt());
    }
}
