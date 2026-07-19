package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Validates crawler header profiles for camouflage-capable requests.
 *
 * <p>Profiles may carry full browser identities, client hints, forwarding headers, session cookies
 * and authorization tokens. Framing-managed headers ({@code content-length},
 * {@code transfer-encoding}) remain rejected because the HTTP client owns those values.
 */
public final class CrawlerHeaderRules {
    public static final CrawlerHeaderProfileId DEFAULT_PROFILE_ID =
            new CrawlerHeaderProfileId("header_default");
    private static final int MAXIMUM_ADDITIONAL_HEADERS = 48;
    private static final int MAXIMUM_TOTAL_HEADER_LENGTH = 24_576;
    private static final int MAXIMUM_RETAIN_HEADERS = 32;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Headers the Java HttpClient / framing layer must own. */
    private static final Set<String> FRAMING_MANAGED_HEADERS = Set.of(
            "content-length",
            "transfer-encoding");

    private CrawlerHeaderRules() {
    }

    public static String displayName(String value) {
        return requiredValue(value, "displayName", 120);
    }

    public static String userAgent(String value) {
        return requiredValue(value, "userAgent", 500);
    }

    public static String optionalAccept(String value) {
        return optionalValue(value, "accept", 2_048);
    }

    public static String optionalAcceptLanguage(String value) {
        return optionalValue(value, "acceptLanguage", 500);
    }

    public static Map<String, String> additionalHeaders(Map<String, String> values) {
        Objects.requireNonNull(values, "additionalHeaders");
        if (values.size() > MAXIMUM_ADDITIONAL_HEADERS) {
            throw new IllegalArgumentException("Crawler header profile contains too many additional headers");
        }
        var normalized = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        var totalLength = 0;
        for (var entry : values.entrySet()) {
            var name = requiredValue(entry.getKey(), "headerName", 80);
            var lowerName = name.toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches() || FRAMING_MANAGED_HEADERS.contains(lowerName)) {
                throw new IllegalArgumentException(
                        "Crawler header profile contains an invalid or framing-managed header: " + name);
            }
            var headerValue = requiredValue(entry.getValue(), "headerValue", 4_096);
            totalLength += name.length() + headerValue.length();
            if (totalLength > MAXIMUM_TOTAL_HEADER_LENGTH) {
                throw new IllegalArgumentException("Crawler header profile is too large");
            }
            if (normalized.put(name, headerValue) != null) {
                throw new IllegalArgumentException(
                        "Crawler header profile contains a duplicate header name");
            }
        }
        return Map.copyOf(normalized);
    }

    public static CrawlerBrowserTemplate browserTemplate(CrawlerBrowserTemplate value) {
        return value == null ? CrawlerBrowserTemplate.NONE : value;
    }

    public static CrawlerCaptchaBypassProvider captchaBypassProvider(CrawlerCaptchaBypassProvider value) {
        return value == null ? CrawlerCaptchaBypassProvider.NONE : value;
    }

    public static Set<String> crossOriginRetainHeaders(Set<String> values) {
        Objects.requireNonNull(values, "crossOriginRetainHeaders");
        if (values.size() > MAXIMUM_RETAIN_HEADERS) {
            throw new IllegalArgumentException("Too many cross-origin retain headers");
        }
        var normalized = new LinkedHashSet<String>();
        for (var raw : values) {
            var name = requiredValue(raw, "crossOriginRetainHeader", 80);
            if (!HEADER_NAME.matcher(name).matches()
                    || FRAMING_MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Cross-origin retain header is invalid: " + name);
            }
            normalized.add(name);
        }
        return Set.copyOf(normalized);
    }

    public static void validateBypassConfiguration(
            boolean captchaBypassEnabled,
            CrawlerCaptchaBypassProvider provider) {
        if (captchaBypassEnabled && !provider.active()) {
            throw new IllegalArgumentException(
                    "captchaBypassEnabled requires an active captchaBypassProvider");
        }
    }

    private static String optionalValue(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredValue(value, field, maximumLength);
    }

    private static String requiredValue(String value, String field, int maximumLength) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()
                || normalized.length() > maximumLength
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
