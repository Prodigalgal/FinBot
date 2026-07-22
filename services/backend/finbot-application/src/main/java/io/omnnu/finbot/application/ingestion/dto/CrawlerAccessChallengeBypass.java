package io.omnnu.finbot.application.ingestion.dto;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Solved challenge material merged into a subsequent crawl attempt. */
public record CrawlerAccessChallengeBypass(
        Map<String, String> extraHeaders,
        Map<String, String> cookies,
        String providerDetail) {
    private static final Pattern COOKIE_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");

    public CrawlerAccessChallengeBypass {
        extraHeaders = Map.copyOf(extraHeaders == null ? Map.of() : extraHeaders);
        cookies = Map.copyOf(cookies == null ? Map.of() : cookies);
        cookies.forEach(CrawlerAccessChallengeBypass::validateCookie);
        providerDetail = providerDetail == null ? "" : providerDetail.strip();
    }

    public static CrawlerAccessChallengeBypass empty() {
        return new CrawlerAccessChallengeBypass(Map.of(), Map.of(), "");
    }

    public CrawlerAccessChallengeBypass mergeCookies(Map<String, String> more) {
        Objects.requireNonNull(more, "more");
        if (more.isEmpty()) {
            return this;
        }
        var merged = new java.util.LinkedHashMap<>(cookies);
        merged.putAll(more);
        return new CrawlerAccessChallengeBypass(extraHeaders, merged, providerDetail);
    }

    public String cookieHeader() {
        if (cookies.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            builder.append(name).append('=').append(value);
        });
        return builder.toString();
    }

    private static void validateCookie(String name, String value) {
        if (name == null || !COOKIE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("challenge cookie name is invalid");
        }
        if (value == null || value.length() > 4_096
                || value.indexOf(';') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("challenge cookie value is invalid");
        }
    }
}
