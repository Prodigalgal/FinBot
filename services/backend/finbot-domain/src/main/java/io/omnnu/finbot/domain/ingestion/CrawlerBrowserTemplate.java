package io.omnnu.finbot.domain.ingestion;

import java.util.Locale;

/** Named browser identity suites applied on top of a crawler header profile. */
public enum CrawlerBrowserTemplate {
    NONE,
    CHROME_WINDOWS,
    CHROME_MAC,
    FIREFOX_WINDOWS,
    EDGE_WINDOWS,
    CUSTOM;

    public static CrawlerBrowserTemplate from(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        return CrawlerBrowserTemplate.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
