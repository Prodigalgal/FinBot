package io.omnnu.finbot.application.ingestion.service;

import io.omnnu.finbot.application.ingestion.dto.CrawlerAccessChallenge;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Heuristic detector for CAPTCHA / WAF interstitial pages (C1 classification).
 *
 * <p>Also classifies HTTP 200 challenge interstitials (Anubis / bot walls) that never expose the
 * intended JSON API. Detection is best-effort and never executes challenge JavaScript.
 */
public final class CrawlerAccessChallengeDetector {
    private static final Pattern TURNSTILE_SITEKEY = Pattern.compile(
            "data-sitekey\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECAPTCHA_SITEKEY = Pattern.compile(
            "(?:data-sitekey|grecaptcha\\.render)\\s*[=(]\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HCAPTCHA_SITEKEY = Pattern.compile(
            "data-sitekey\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "<title>\\s*(.*?)\\s*</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public Optional<CrawlerAccessChallenge> detect(
            URI pageUrl,
            int statusCode,
            String contentType,
            byte[] body,
            Map<String, String> responseHeaders) {
        var text = preview(body);
        var lower = text.toLowerCase(Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        var cfRay = header(responseHeaders, "cf-ray");
        var server = header(responseHeaders, "server").toLowerCase(Locale.ROOT);
        var setCookie = header(responseHeaders, "set-cookie").toLowerCase(Locale.ROOT);
        var title = title(text).toLowerCase(Locale.ROOT);
        var html = isHtmlOrText(type) || type.isBlank() || lower.contains("<html") || lower.contains("<!doctype");

        if (containsAny(lower,
                "cf-turnstile",
                "challenges.cloudflare.com/turnstile",
                "turnstile.render")) {
            return challenge(
                    CrawlerAccessChallenge.Kind.CLOUDFLARE_TURNSTILE,
                    pageUrl,
                    firstGroup(TURNSTILE_SITEKEY, text),
                    text,
                    responseHeaders,
                    statusCode);
        }
        if ((statusCode == 403 || statusCode == 503 || statusCode == 429 || statusCode == 200)
                && (isPresent(cfRay)
                || server.contains("cloudflare")
                || containsAny(lower,
                        "cf-browser-verification",
                        "just a moment",
                        "attention required",
                        "cf-challenge",
                        "cdn-cgi/challenge"))) {
            return challenge(
                    CrawlerAccessChallenge.Kind.CLOUDFLARE_MANAGED,
                    pageUrl,
                    firstGroup(TURNSTILE_SITEKEY, text),
                    text,
                    responseHeaders,
                    statusCode);
        }
        // Anubis / Techaro wall — common on public SearXNG (including HTTP 200/400 challenge pages).
        if (html && (containsAny(lower,
                "anubis",
                "withanubis",
                "oh noes",
                "/.within.website/",
                "xess.min.css",
                "making sure you're not a bot",
                "making sure you&#39;re not a bot",
                "proof-of-work",
                "techaro")
                || containsAny(title, "oh noes", "not a bot", "anubis"))) {
            return challenge(
                    CrawlerAccessChallenge.Kind.ANUBIS,
                    pageUrl,
                    null,
                    text,
                    responseHeaders,
                    statusCode);
        }
        if (containsAny(lower, "hcaptcha.com", "h-captcha", "hcaptcha-box")
                || setCookie.contains("hcaptcha")) {
            String siteKey = null;
            var matcher = HCAPTCHA_SITEKEY.matcher(text);
            if (matcher.find() && (lower.contains("hcaptcha") || lower.contains("h-captcha"))) {
                siteKey = matcher.group(1);
            }
            return challenge(
                    CrawlerAccessChallenge.Kind.HCAPTCHA,
                    pageUrl,
                    siteKey,
                    text,
                    responseHeaders,
                    statusCode);
        }
        if (containsAny(lower, "recaptcha", "www.google.com/recaptcha", "grecaptcha")
                && (statusCode >= 400 || containsAny(title, "captcha", "verify", "bot", "human"))) {
            return challenge(
                    CrawlerAccessChallenge.Kind.RECAPTCHA_V2,
                    pageUrl,
                    firstGroup(RECAPTCHA_SITEKEY, text),
                    text,
                    responseHeaders,
                    statusCode);
        }
        if (containsAny(lower, "datadome", "dd.js", "geo.captcha-delivery.com")
                || setCookie.contains("datadome")
                || server.contains("datadome")) {
            return challenge(
                    CrawlerAccessChallenge.Kind.DATADOME,
                    pageUrl,
                    null,
                    text,
                    responseHeaders,
                    statusCode);
        }
        if (containsAny(lower, "perimeterx", "_px", "px-captcha", "human challenge")
                || setCookie.contains("_px")) {
            return challenge(
                    CrawlerAccessChallenge.Kind.PERIMETERX,
                    pageUrl,
                    null,
                    text,
                    responseHeaders,
                    statusCode);
        }
        if (statusCode == 429
                && (html || type.contains("application/json")
                || text.isBlank()
                || containsAny(lower, "rate limit", "too many requests", "retry later"))) {
            return challenge(
                    CrawlerAccessChallenge.Kind.RATE_LIMITED,
                    pageUrl,
                    null,
                    text,
                    responseHeaders,
                    statusCode);
        }
        if ((statusCode == 401 || statusCode == 403 || statusCode == 429 || statusCode == 503
                || (statusCode == 200 && html && !looksLikeJson(text)))
                && html
                && containsAny(lower,
                        "captcha",
                        "challenge",
                        "access denied",
                        "bot detection",
                        "enable javascript",
                        "verify you are human",
                        "are you a robot",
                        "checking your browser",
                        "not a bot",
                        "browser check")) {
            return challenge(
                    CrawlerAccessChallenge.Kind.GENERIC_JS_CHALLENGE,
                    pageUrl,
                    null,
                    text,
                    responseHeaders,
                    statusCode);
        }
        if (statusCode == 403 || statusCode == 401) {
            return challenge(
                    CrawlerAccessChallenge.Kind.UNKNOWN_BLOCK,
                    pageUrl,
                    null,
                    text,
                    responseHeaders,
                    statusCode);
        }
        return Optional.empty();
    }

    private static boolean looksLikeJson(String text) {
        var trimmed = text.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String title(String html) {
        var matcher = TITLE.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replaceAll("\\s+", " ").strip();
    }

    private static Optional<CrawlerAccessChallenge> challenge(
            CrawlerAccessChallenge.Kind kind,
            URI pageUrl,
            String siteKey,
            String text,
            Map<String, String> responseHeaders,
            int statusCode) {
        return Optional.of(new CrawlerAccessChallenge(
                kind,
                pageUrl,
                siteKey,
                truncate(text),
                responseHeaders,
                statusCode,
                null));
    }

    private static boolean isHtmlOrText(String type) {
        return type.contains("text/html")
                || type.contains("text/plain")
                || type.contains("application/xhtml");
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (var needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        var limit = Math.min(body.length, 64_000);
        return new String(body, 0, limit, StandardCharsets.UTF_8);
    }

    private static String truncate(String value) {
        if (value.length() <= 2_000) {
            return value;
        }
        return value.substring(0, 2_000);
    }

    private static String firstGroup(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue() == null ? "" : entry.getValue();
            }
        }
        return "";
    }
}
