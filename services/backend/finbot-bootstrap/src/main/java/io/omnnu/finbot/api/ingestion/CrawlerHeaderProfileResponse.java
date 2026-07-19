package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record CrawlerHeaderProfileResponse(
        String profileId,
        String displayName,
        String userAgent,
        String accept,
        String acceptLanguage,
        Map<String, String> additionalHeaders,
        CrawlerBrowserTemplate browserTemplate,
        boolean retainSensitiveHeadersOnCrossOriginRedirect,
        Set<String> crossOriginRetainHeaders,
        boolean captchaBypassEnabled,
        CrawlerCaptchaBypassProvider captchaBypassProvider,
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
                profile.browserTemplate(),
                profile.retainSensitiveHeadersOnCrossOriginRedirect(),
                profile.crossOriginRetainHeaders(),
                profile.captchaBypassEnabled(),
                profile.captchaBypassProvider(),
                profile.enabled(),
                profile.usageCount(),
                profile.version(),
                profile.updatedAt());
    }
}
