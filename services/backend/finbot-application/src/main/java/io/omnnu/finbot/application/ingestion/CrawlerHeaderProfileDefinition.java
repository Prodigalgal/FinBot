package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import java.util.Map;
import java.util.Set;

public record CrawlerHeaderProfileDefinition(
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
        boolean enabled) {
    public CrawlerHeaderProfileDefinition {
        displayName = CrawlerHeaderRules.displayName(displayName);
        userAgent = CrawlerHeaderRules.userAgent(userAgent);
        accept = CrawlerHeaderRules.optionalAccept(accept);
        acceptLanguage = CrawlerHeaderRules.optionalAcceptLanguage(acceptLanguage);
        additionalHeaders = CrawlerHeaderRules.additionalHeaders(
                additionalHeaders == null ? Map.of() : additionalHeaders);
        browserTemplate = CrawlerHeaderRules.browserTemplate(browserTemplate);
        crossOriginRetainHeaders = CrawlerHeaderRules.crossOriginRetainHeaders(
                crossOriginRetainHeaders == null ? Set.of() : crossOriginRetainHeaders);
        captchaBypassProvider = CrawlerHeaderRules.captchaBypassProvider(captchaBypassProvider);
        CrawlerHeaderRules.validateBypassConfiguration(captchaBypassEnabled, captchaBypassProvider);
    }
}
