package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class CrawlerHeaderRules {
    public static final CrawlerHeaderProfileId DEFAULT_PROFILE_ID =
            new CrawlerHeaderProfileId("header_default");
    private static final int MAXIMUM_ADDITIONAL_HEADERS = 16;
    private static final int MAXIMUM_TOTAL_HEADER_LENGTH = 8_192;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESERVED_OR_SENSITIVE_HEADERS = Set.of(
            "accept",
            "accept-language",
            "authorization",
            "connection",
            "content-length",
            "content-type",
            "cookie",
            "forwarded",
            "host",
            "origin",
            "proxy-authorization",
            "proxy-connection",
            "referer",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "user-agent",
            "via",
            "x-api-key",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto",
            "x-real-ip",
            "x-subscription-token");

    private CrawlerHeaderRules() {
    }

    public static String displayName(String value) {
        return requiredValue(value, "displayName", 120);
    }

    public static String userAgent(String value) {
        var normalized = requiredValue(value, "userAgent", 500);
        if (!normalized.startsWith("FinBot/")
                || !(normalized.contains("contact:") || normalized.contains("+http"))) {
            throw new IllegalArgumentException(
                    "Crawler User-Agent must identify FinBot and include a contact address");
        }
        return normalized;
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
            if (!HEADER_NAME.matcher(name).matches()
                    || RESERVED_OR_SENSITIVE_HEADERS.contains(lowerName)
                    || lowerName.startsWith("sec-fetch-")
                    || lowerName.startsWith("sec-ch-ua")
                    || lowerName.contains("api-key")
                    || lowerName.contains("apikey")
                    || lowerName.endsWith("-token")
                    || lowerName.endsWith("-secret")) {
                throw new IllegalArgumentException(
                        "Crawler header profile contains a reserved or sensitive header");
            }
            var headerValue = requiredValue(entry.getValue(), "headerValue", 2_048);
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
