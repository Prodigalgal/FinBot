package io.omnnu.finbot.application.ingestion.dto;

import java.net.URI;
import java.util.Objects;

public record AiWebSearchCitation(
        URI url,
        String title,
        String citedText) {
    public AiWebSearchCitation {
        Objects.requireNonNull(url, "url");
        title = normalize(title, 500);
        citedText = normalize(citedText, 4_000);
    }

    private static String normalize(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }
}
