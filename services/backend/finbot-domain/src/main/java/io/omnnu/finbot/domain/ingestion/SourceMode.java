package io.omnnu.finbot.domain.ingestion;

public enum SourceMode {
    RSS,
    FIRECRAWL_SCRAPE,
    FIRECRAWL_SEARCH,
    FIRECRAWL_SEARCH_THEN_SCRAPE,
    STRUCTURED_API,
    EXCHANGE_PUBLIC_API;

    public boolean firecrawl() {
        return switch (this) {
            case FIRECRAWL_SCRAPE, FIRECRAWL_SEARCH, FIRECRAWL_SEARCH_THEN_SCRAPE -> true;
            case RSS, STRUCTURED_API, EXCHANGE_PUBLIC_API -> false;
        };
    }
}
