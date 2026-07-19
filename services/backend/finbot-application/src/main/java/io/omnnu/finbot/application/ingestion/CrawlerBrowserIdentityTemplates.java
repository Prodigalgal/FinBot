package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Builds consistent browser identity header suites for camouflage profiles. */
public final class CrawlerBrowserIdentityTemplates {
    private CrawlerBrowserIdentityTemplates() {
    }

    public static AppliedIdentity apply(
            CrawlerBrowserTemplate template,
            String userAgent,
            String accept,
            String acceptLanguage,
            Map<String, String> additionalHeaders) {
        Objects.requireNonNull(template, "template");
        var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        additionalHeaders.forEach(headers::put);
        var resolvedUserAgent = userAgent;
        var resolvedAccept = accept;
        var resolvedLanguage = acceptLanguage;
        switch (template) {
            case NONE, CUSTOM -> {
                // Profile values stand alone.
            }
            case CHROME_WINDOWS -> {
                resolvedUserAgent = preferBrowserUa(
                        userAgent,
                        "Chrome",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                putIfAbsent(headers, "sec-ch-ua",
                        "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"");
                putIfAbsent(headers, "sec-ch-ua-mobile", "?0");
                putIfAbsent(headers, "sec-ch-ua-platform", "\"Windows\"");
                putIfAbsent(headers, "sec-fetch-dest", "document");
                putIfAbsent(headers, "sec-fetch-mode", "navigate");
                putIfAbsent(headers, "sec-fetch-site", "none");
                putIfAbsent(headers, "sec-fetch-user", "?1");
                putIfAbsent(headers, "upgrade-insecure-requests", "1");
                putIfAbsent(headers, "dnt", "1");
                resolvedAccept = prefer(accept,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                                + "image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
                resolvedLanguage = prefer(acceptLanguage, "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
            }
            case CHROME_MAC -> {
                resolvedUserAgent = preferBrowserUa(
                        userAgent,
                        "Chrome",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
                putIfAbsent(headers, "sec-ch-ua",
                        "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"");
                putIfAbsent(headers, "sec-ch-ua-mobile", "?0");
                putIfAbsent(headers, "sec-ch-ua-platform", "\"macOS\"");
                putIfAbsent(headers, "sec-fetch-dest", "document");
                putIfAbsent(headers, "sec-fetch-mode", "navigate");
                putIfAbsent(headers, "sec-fetch-site", "none");
                putIfAbsent(headers, "sec-fetch-user", "?1");
                putIfAbsent(headers, "upgrade-insecure-requests", "1");
                resolvedAccept = prefer(accept,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                                + "image/webp,image/apng,*/*;q=0.8");
                resolvedLanguage = prefer(acceptLanguage, "en-US,en;q=0.9");
            }
            case FIREFOX_WINDOWS -> {
                resolvedUserAgent = preferBrowserUa(
                        userAgent,
                        "Firefox",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0");
                putIfAbsent(headers, "sec-fetch-dest", "document");
                putIfAbsent(headers, "sec-fetch-mode", "navigate");
                putIfAbsent(headers, "sec-fetch-site", "none");
                putIfAbsent(headers, "sec-fetch-user", "?1");
                putIfAbsent(headers, "upgrade-insecure-requests", "1");
                putIfAbsent(headers, "te", "trailers");
                resolvedAccept = prefer(accept,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                                + "image/webp,*/*;q=0.8");
                resolvedLanguage = prefer(acceptLanguage, "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
            }
            case EDGE_WINDOWS -> {
                resolvedUserAgent = preferBrowserUa(
                        userAgent,
                        "Edg/",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0");
                putIfAbsent(headers, "sec-ch-ua",
                        "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"");
                putIfAbsent(headers, "sec-ch-ua-mobile", "?0");
                putIfAbsent(headers, "sec-ch-ua-platform", "\"Windows\"");
                putIfAbsent(headers, "sec-fetch-dest", "document");
                putIfAbsent(headers, "sec-fetch-mode", "navigate");
                putIfAbsent(headers, "sec-fetch-site", "none");
                putIfAbsent(headers, "sec-fetch-user", "?1");
                putIfAbsent(headers, "upgrade-insecure-requests", "1");
                resolvedAccept = prefer(accept,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,"
                                + "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
                resolvedLanguage = prefer(acceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
            }
        }
        return new AppliedIdentity(resolvedUserAgent, resolvedAccept, resolvedLanguage, Map.copyOf(headers));
    }

    private static String prefer(String configured, String templateDefault) {
        return configured == null || configured.isBlank() ? templateDefault : configured;
    }

    private static String preferBrowserUa(String configured, String familyToken, String templateDefault) {
        if (configured == null || configured.isBlank()) {
            return templateDefault;
        }
        if (configured.toLowerCase(Locale.ROOT).contains(familyToken.toLowerCase(Locale.ROOT))
                && configured.length() >= 40) {
            return configured;
        }
        return templateDefault;
    }

    private static void putIfAbsent(Map<String, String> headers, String name, String value) {
        for (var key : headers.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return;
            }
        }
        headers.put(name, value);
    }

    public record AppliedIdentity(
            String userAgent,
            String accept,
            String acceptLanguage,
            Map<String, String> additionalHeaders) {
        public AppliedIdentity {
            userAgent = Objects.requireNonNull(userAgent, "userAgent").strip();
            additionalHeaders = Map.copyOf(additionalHeaders);
        }

        public boolean hasHeader(String name) {
            var needle = name.toLowerCase(Locale.ROOT);
            return additionalHeaders.keySet().stream()
                    .anyMatch(key -> key.toLowerCase(Locale.ROOT).equals(needle));
        }
    }
}
