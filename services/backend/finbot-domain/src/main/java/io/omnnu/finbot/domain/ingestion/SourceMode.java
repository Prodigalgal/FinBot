package io.omnnu.finbot.domain.ingestion;

public enum SourceMode {
    RSS,
    HTML_DOCUMENT,
    SEARCH_DISCOVERY,
    FIRECRAWL_SCRAPE,
    FIRECRAWL_SEARCH,
    FIRECRAWL_SEARCH_THEN_SCRAPE,
    JSON_API,
    SITEMAP,
    AI_WEB_SEARCH,
    EXCHANGE_PUBLIC_API;

    public boolean firecrawl() {
        return switch (this) {
            case FIRECRAWL_SCRAPE, FIRECRAWL_SEARCH, FIRECRAWL_SEARCH_THEN_SCRAPE -> true;
            case RSS, HTML_DOCUMENT, SEARCH_DISCOVERY, JSON_API, SITEMAP, AI_WEB_SEARCH,
                    EXCHANGE_PUBLIC_API -> false;
        };
    }
}
