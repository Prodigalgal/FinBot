package io.omnnu.finbot.domain.ingestion;

public enum AiWebSearchTool {
    WEB_SEARCH("web_search"),
    GOOGLE_SEARCH("google_search");

    private final String wireName;

    AiWebSearchTool(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
