package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfileDefinition;
import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;

public record CrawlerHeaderProfileMutationRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(max = 500) String userAgent,
        @Size(max = 2_048) String accept,
        @Size(max = 500) String acceptLanguage,
        @Size(max = 48) Map<@NotBlank @Size(max = 80) String, @NotBlank @Size(max = 4_096) String> additionalHeaders,
        @NotNull CrawlerBrowserTemplate browserTemplate,
        boolean retainSensitiveHeadersOnCrossOriginRedirect,
        @Size(max = 32) Set<@NotBlank @Size(max = 80) String> crossOriginRetainHeaders,
        boolean captchaBypassEnabled,
        @NotNull CrawlerCaptchaBypassProvider captchaBypassProvider,
        boolean enabled) {
    CrawlerHeaderProfileDefinition toDefinition() {
        return new CrawlerHeaderProfileDefinition(
                displayName,
                userAgent,
                accept,
                acceptLanguage,
                additionalHeaders == null ? Map.of() : additionalHeaders,
                browserTemplate == null ? CrawlerBrowserTemplate.NONE : browserTemplate,
                retainSensitiveHeadersOnCrossOriginRedirect,
                crossOriginRetainHeaders == null ? Set.of() : crossOriginRetainHeaders,
                captchaBypassEnabled,
                captchaBypassProvider == null ? CrawlerCaptchaBypassProvider.NONE : captchaBypassProvider,
                enabled);
    }
}
