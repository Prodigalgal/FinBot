package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.application.ingestion.service.CrawlerBrowserIdentityTemplates;
import io.omnnu.finbot.application.ingestion.dto.CrawlerHeaderProfile;
import io.omnnu.finbot.application.ingestion.port.out.CrawlerHeaderProfileResolver;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Resolves camouflage-capable request headers from reusable profiles.
 *
 * <p>Supports full browser identity suites, forwarding and client-hint headers, session tokens,
 * and configurable cross-origin redirect retention.
 */
public final class CrawlerRequestHeaderPolicy {
    private static final int MAXIMUM_HEADERS = 64;
    private static final int MAXIMUM_HEADER_NAME_LENGTH = 80;
    private static final int MAXIMUM_HEADER_VALUE_LENGTH = 4_096;
    private static final int MAXIMUM_TOTAL_HEADER_LENGTH = 32_768;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> FRAMING_MANAGED_HEADERS = Set.of(
            "content-length",
            "transfer-encoding");
    private static final Set<String> DEFAULT_SENSITIVE_CROSS_ORIGIN = Set.of(
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

    CrawlerHeaderProfile resolveProfile(SourceId sourceId) {
        var profile = profileResolver.resolve(Objects.requireNonNull(sourceId, "sourceId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Crawler header profile is not configured for source"));
        if (!profile.enabled()) {
            throw new IllegalArgumentException("Crawler header profile is disabled");
        }
        return profile;
    }

    Map<String, String> prepare(SourceId sourceId, Map<String, String> requestedHeaders) {
        var profile = resolveProfile(sourceId);
        return prepare(profile, requestedHeaders);
    }

    Map<String, String> prepare(CrawlerHeaderProfile profile, Map<String, String> requestedHeaders) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(requestedHeaders, "requestedHeaders");
        var identity = CrawlerBrowserIdentityTemplates.apply(
                profile.browserTemplate(),
                profile.userAgent(),
                profile.accept(),
                profile.acceptLanguage(),
                profile.additionalHeaders());
        if (requestedHeaders.size() + identity.additionalHeaders().size() > MAXIMUM_HEADERS) {
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
        identity.additionalHeaders().forEach(result::put);
        if (identity.accept() != null && !identity.accept().isBlank()) {
            result.put("Accept", identity.accept());
        }
        if (identity.acceptLanguage() != null && !identity.acceptLanguage().isBlank()) {
            result.put("Accept-Language", identity.acceptLanguage());
        }
        result.put("User-Agent", identity.userAgent());
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

    Map<String, String> forCrossOriginRedirect(CrawlerHeaderProfile profile, Map<String, String> preparedHeaders) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(preparedHeaders, "preparedHeaders");
        if (profile.retainSensitiveHeadersOnCrossOriginRedirect()) {
            return Map.copyOf(preparedHeaders);
        }
        var retain = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (var name : profile.crossOriginRetainHeaders()) {
            retain.put(name.toLowerCase(Locale.ROOT), name);
        }
        var result = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        preparedHeaders.forEach((name, value) -> {
            if (shouldRetainOnCrossOrigin(name, retain.keySet())) {
                result.put(name, value);
            }
        });
        return Map.copyOf(result);
    }

    private static boolean shouldRetainOnCrossOrigin(String name, Set<String> explicitRetainLower) {
        var normalized = name.toLowerCase(Locale.ROOT);
        if (explicitRetainLower.contains(normalized)) {
            return true;
        }
        if (DEFAULT_SENSITIVE_CROSS_ORIGIN.contains(normalized)
                || normalized.contains("api-key")
                || normalized.contains("apikey")
                || normalized.endsWith("-token")
                || normalized.endsWith("-secret")) {
            return false;
        }
        return true;
    }

    private static String requireName(String value) {
        var name = Objects.requireNonNull(value, "headerName").strip();
        var normalized = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty()
                || name.length() > MAXIMUM_HEADER_NAME_LENGTH
                || !HEADER_NAME.matcher(name).matches()
                || FRAMING_MANAGED_HEADERS.contains(normalized)) {
            throw new IllegalArgumentException("Crawler request header name is not allowed: " + name);
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
}
