package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Detected upstream CAPTCHA / WAF challenge.
 *
 * <p>Used by C1 classification (error codes + observability) and optional C3 bypass.
 */
public record CrawlerAccessChallenge(
        Kind kind,
        URI pageUrl,
        String siteKey,
        String challengeHtmlSnippet,
        Map<String, String> responseHeaders,
        int statusCode,
        CrawlerCaptchaBypassProvider preferredProvider) {
    public CrawlerAccessChallenge {
        kind = Objects.requireNonNull(kind, "kind");
        pageUrl = Objects.requireNonNull(pageUrl, "pageUrl");
        challengeHtmlSnippet = challengeHtmlSnippet == null ? "" : challengeHtmlSnippet;
        responseHeaders = Map.copyOf(responseHeaders == null ? Map.of() : responseHeaders);
        preferredProvider = preferredProvider == null
                ? CrawlerCaptchaBypassProvider.NONE
                : preferredProvider;
    }

    public Optional<String> siteKeyOptional() {
        return siteKey == null || siteKey.isBlank() ? Optional.empty() : Optional.of(siteKey);
    }

    /** Suffix for crawler error codes, e.g. {@code CHALLENGE_CLOUDFLARE_TURNSTILE}. */
    public String errorCodeSuffix() {
        return switch (kind) {
            case CLOUDFLARE_TURNSTILE -> "CHALLENGE_CLOUDFLARE_TURNSTILE";
            case CLOUDFLARE_MANAGED -> "CHALLENGE_CLOUDFLARE_MANAGED";
            case RECAPTCHA_V2 -> "CHALLENGE_RECAPTCHA_V2";
            case HCAPTCHA -> "CHALLENGE_HCAPTCHA";
            case ANUBIS -> "CHALLENGE_ANUBIS";
            case DATADOME -> "CHALLENGE_DATADOME";
            case PERIMETERX -> "CHALLENGE_PERIMETERX";
            case GENERIC_JS_CHALLENGE -> "CHALLENGE_JS";
            case RATE_LIMITED -> "RATE_LIMITED";
            case UNKNOWN_BLOCK -> "ACCESS_BLOCKED";
        };
    }

    public boolean blocked() {
        return kind != Kind.RATE_LIMITED || statusCode == 429 || statusCode == 403 || statusCode == 401;
    }

    public String safeMessage(String safeName) {
        var host = pageUrl.getHost() == null ? "unknown-host" : pageUrl.getHost();
        var base = switch (kind) {
            case CLOUDFLARE_TURNSTILE ->
                    safeName + " was blocked by Cloudflare Turnstile on " + host;
            case CLOUDFLARE_MANAGED ->
                    safeName + " was blocked by a Cloudflare managed challenge on " + host;
            case RECAPTCHA_V2 ->
                    safeName + " was blocked by reCAPTCHA on " + host;
            case HCAPTCHA ->
                    safeName + " was blocked by hCaptcha on " + host;
            case ANUBIS ->
                    safeName + " was blocked by an Anubis proof-of-work challenge on " + host;
            case DATADOME ->
                    safeName + " was blocked by DataDome on " + host;
            case PERIMETERX ->
                    safeName + " was blocked by PerimeterX on " + host;
            case GENERIC_JS_CHALLENGE ->
                    safeName + " was blocked by a JavaScript / bot challenge on " + host;
            case RATE_LIMITED ->
                    safeName + " was rate-limited on " + host;
            case UNKNOWN_BLOCK ->
                    safeName + " was access-blocked on " + host + " (HTTP " + statusCode + ")";
        };
        if (siteKeyOptional().isPresent()) {
            return base + "; site key detected";
        }
        return base;
    }

    public String observationLabel() {
        return "challenge/" + kind.name().toLowerCase(Locale.ROOT);
    }

    public enum Kind {
        CLOUDFLARE_TURNSTILE,
        CLOUDFLARE_MANAGED,
        RECAPTCHA_V2,
        HCAPTCHA,
        ANUBIS,
        DATADOME,
        PERIMETERX,
        GENERIC_JS_CHALLENGE,
        RATE_LIMITED,
        UNKNOWN_BLOCK
    }
}
