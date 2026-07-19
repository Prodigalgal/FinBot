package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfileDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CrawlerHeaderProfileMutationRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(max = 500) String userAgent,
        @Size(max = 2_048) String accept,
        @Size(max = 500) String acceptLanguage,
        @Size(max = 16) Map<@NotBlank @Size(max = 80) String, @NotBlank @Size(max = 2_048) String> additionalHeaders,
        boolean enabled) {
    CrawlerHeaderProfileDefinition toDefinition() {
        return new CrawlerHeaderProfileDefinition(
                displayName,
                userAgent,
                accept,
                acceptLanguage,
                additionalHeaders == null ? Map.of() : additionalHeaders,
                enabled);
    }
}
