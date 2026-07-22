package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.application.ingestion.service.CrawlerHeaderRules;

import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CrawlerHeaderProfile(
        CrawlerHeaderProfileId profileId,
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
    public CrawlerHeaderProfile {
        profileId = Objects.requireNonNull(profileId, "profileId");
        displayName = CrawlerHeaderRules.displayName(displayName);
        userAgent = CrawlerHeaderRules.userAgent(userAgent);
        accept = CrawlerHeaderRules.optionalAccept(accept);
        acceptLanguage = CrawlerHeaderRules.optionalAcceptLanguage(acceptLanguage);
        additionalHeaders = CrawlerHeaderRules.additionalHeaders(additionalHeaders);
        browserTemplate = CrawlerHeaderRules.browserTemplate(browserTemplate);
        crossOriginRetainHeaders = CrawlerHeaderRules.crossOriginRetainHeaders(
                crossOriginRetainHeaders == null ? Set.of() : crossOriginRetainHeaders);
        captchaBypassProvider = CrawlerHeaderRules.captchaBypassProvider(captchaBypassProvider);
        CrawlerHeaderRules.validateBypassConfiguration(captchaBypassEnabled, captchaBypassProvider);
        if (usageCount < 0 || version < 0) {
            throw new IllegalArgumentException("Crawler header profile counters are invalid");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
