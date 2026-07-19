package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfileResolver;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Applies transparent crawler identity, content negotiation defaults, and redirect-safe header rules. */
public final class CrawlerRequestHeaderPolicy {
    private static final int MAXIMUM_HEADERS = 32;
    private static final int MAXIMUM_HEADER_NAME_LENGTH = 80;
    private static final int MAXIMUM_HEADER_VALUE_LENGTH = 2_048;
    private static final int MAXIMUM_TOTAL_HEADER_LENGTH = 16_384;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "connection",
            "content-length",
            "forwarded",
            "host",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "via",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto",
            "x-real-ip");
    private static final Set<String> CROSS_ORIGIN_HEADERS = Set.of(
            "authorization",
            "cookie",
            "origin",
            "proxy-authorization",
            "referer");
    private static final String DEFAULT_ACCEPT =
            "text/html,application/xhtml+xml,application/json;q=0.9,application/xml;q=0.8,text/plain;q=0.7,*/*;q=0.5";
    private static final String DEFAULT_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";

    private final CrawlerHeaderProfileResolver profileResolver;

    public CrawlerRequestHeaderPolicy(CrawlerHeaderProfileResolver profileResolver) {
        this.profileResolver = Objects.requireNonNull(profileResolver, "profileResolver");
    }

    Map<String, String> prepare(SourceId sourceId, Map<String, String> requestedHeaders) {
        var profile = profileResolver.resolve(Objects.requireNonNull(sourceId, "sourceId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Crawler header profile is not configured for source"));
        if (!profile.enabled()) {
            throw new IllegalArgumentException("Crawler header profile is disabled");
        }
        Objects.requireNonNull(requestedHeaders, "requestedHeaders");
        if (requestedHeaders.size() + profile.additionalHeaders().size() > MAXIMUM_HEADERS) {
            throw new IllegalArgumentException("Too many crawler request headers");
        }
        var result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        var totalLength = 0;
        for (var entry : requestedHeaders.entrySet()) {
            var name = requireName(entry.getKey());
            var value = requireValue(entry.getValue(), "headerValue", MAXIMUM_HEADER_VALUE_LENGTH);
            totalLength += name.length() + value.length();
            if (totalLength > MAXIMUM_TOTAL_HEADER_LENGTH) {
                throw new IllegalArgumentException("Crawler request headers are too large");
            }
            result.put(name, value);
        }
        profile.additionalHeaders().forEach(result::put);
        if (profile.accept() != null) {
            result.put("Accept", profile.accept());
        }
        if (profile.acceptLanguage() != null) {
            result.put("Accept-Language", profile.acceptLanguage());
        }
        result.remove("User-Agent");
        result.put("User-Agent", profile.userAgent());
        result.putIfAbsent("Accept", DEFAULT_ACCEPT);
        result.putIfAbsent("Accept-Language", DEFAULT_ACCEPT_LANGUAGE);
        var combinedLength = result.entrySet().stream()
                .mapToInt(entry -> entry.getKey().length() + entry.getValue().length())
                .sum();
        if (combinedLength > MAXIMUM_TOTAL_HEADER_LENGTH) {
            throw new IllegalArgumentException("Crawler request headers are too large");
        }
        return Map.copyOf(result);
    }

    Map<String, String> forCrossOriginRedirect(Map<String, String> preparedHeaders) {
        var result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        preparedHeaders.forEach((name, value) -> {
            if (!sensitiveAcrossOrigins(name)) {
                result.put(name, value);
            }
        });
        return Map.copyOf(result);
    }

    private static String requireName(String value) {
        var name = Objects.requireNonNull(value, "headerName").strip();
        var normalized = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty()
                || name.length() > MAXIMUM_HEADER_NAME_LENGTH
                || !HEADER_NAME.matcher(name).matches()
                || FORBIDDEN_HEADERS.contains(normalized)
                || normalized.startsWith("sec-fetch-")
                || normalized.startsWith("sec-ch-ua")) {
            throw new IllegalArgumentException("Crawler request header name is not allowed");
        }
        return name;
    }

    private static String requireValue(String value, String field, int maximumLength) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()
                || normalized.length() > maximumLength
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static boolean sensitiveAcrossOrigins(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        return CROSS_ORIGIN_HEADERS.contains(normalized)
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.endsWith("-token")
                || normalized.endsWith("-secret");
    }
}
