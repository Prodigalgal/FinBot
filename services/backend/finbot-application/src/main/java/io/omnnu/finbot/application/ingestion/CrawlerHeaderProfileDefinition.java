package io.omnnu.finbot.application.ingestion;

import java.util.Map;

public record CrawlerHeaderProfileDefinition(
        String displayName,
        String userAgent,
        String accept,
        String acceptLanguage,
        Map<String, String> additionalHeaders,
        boolean enabled) {
    public CrawlerHeaderProfileDefinition {
        displayName = CrawlerHeaderRules.displayName(displayName);
        userAgent = CrawlerHeaderRules.userAgent(userAgent);
        accept = CrawlerHeaderRules.optionalAccept(accept);
        acceptLanguage = CrawlerHeaderRules.optionalAcceptLanguage(acceptLanguage);
        additionalHeaders = CrawlerHeaderRules.additionalHeaders(additionalHeaders);
    }
}
