package io.omnnu.finbot.application.ingestion;

import java.util.Map;
import java.util.Objects;

/** Solved challenge material merged into a subsequent crawl attempt. */
public record CrawlerAccessChallengeBypass(
        Map<String, String> extraHeaders,
        Map<String, String> cookies,
        String providerDetail) {
    public CrawlerAccessChallengeBypass {
        extraHeaders = Map.copyOf(extraHeaders == null ? Map.of() : extraHeaders);
        cookies = Map.copyOf(cookies == null ? Map.of() : cookies);
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
}
