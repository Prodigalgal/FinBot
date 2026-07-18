package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.ContentEnvelopeBuilder;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
final class HtmlSourceCollector implements SourceCollectorAdapter {
    private static final int MAXIMUM_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final String USER_AGENT = "FinBot/2.0 (+https://github.com/omnnu/FinBot)";
    private static final Pattern CHARSET = Pattern.compile(
            "(?i)charset\\s*=\\s*[\\\"']?([a-zA-Z0-9._-]+)");

    private final CrawlerTransport transport;
    private final ContentEnvelopeBuilder envelopeBuilder;

    HtmlSourceCollector(
            CrawlerTransport transport,
            JsoupContentEnvelopeBuilder envelopeBuilder) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.envelopeBuilder = Objects.requireNonNull(envelopeBuilder, "envelopeBuilder");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.HTML_DOCUMENT;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        if (source.seedUrls().isEmpty()) {
            throw new SourceCollectionException(
                    "HTML_SEED_NOT_CONFIGURED",
                    "HTML source has no seed URL",
                    true);
        }
        var result = new ArrayList<CollectedPayload>();
        var limit = Math.min(source.maximumResults(), source.seedUrls().size());
        for (var index = 0; index < limit; index++) {
            result.add(fetch(source, query, source.seedUrls().get(index)));
        }
        return List.copyOf(result);
    }

    private CollectedPayload fetch(InformationSource source, String query, URI targetUrl) {
        if (source.outboundRoute() == null) {
            throw new SourceCollectionException(
                    "HTML_PROXY_REQUIRED",
                    "First-party HTML source has no WEB_CRAWL route",
                    true);
        }
        var response = transport.get(new CrawlerTransport.Request(
                targetUrl,
                source.outboundRoute(),
                Map.of(
                        "Accept", "text/html,application/xhtml+xml;q=0.9,application/json;q=0.7",
                        "User-Agent", USER_AGENT),
                REQUEST_TIMEOUT,
                MAXIMUM_RESPONSE_BYTES,
                3,
                true,
                "HTML",
                "HTML source"));
        var contentType = "application/octet-stream".equals(response.contentType())
                ? "text/html; charset=UTF-8"
                : response.contentType();
        if (!isSupportedContentType(contentType)) {
            throw new SourceCollectionException(
                    "HTML_CONTENT_TYPE_UNSUPPORTED",
                    "HTML source returned an unsupported content type",
                    false);
        }
        var rawContent = decode(response.body(), contentType);
        var metadata = metadata(targetUrl, rawContent, contentType, response.proxyRoute());
        var payload = new CollectedPayload(
                targetUrl,
                canonicalUrl(targetUrl, metadata),
                query,
                metadata.get("title"),
                response.statusCode(),
                contentType,
                rawContent,
                response.responseHeaders(),
                Map.of(
                        "collector", "first_party_html",
                        "proxy_route", response.proxyRoute(),
                        "source_tier", source.tier().name(),
                        "fetch_attempts", Integer.toString(response.attempts())),
                null,
                response.fetchedAt());
        return payload.withEnvelope(envelopeBuilder.build(payload));
    }

    private static boolean isSupportedContentType(String contentType) {
        var normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("text/html")
                || normalized.contains("application/xhtml+xml")
                || normalized.contains("application/json");
    }

    private static String decode(byte[] body, String contentType) {
        var matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return new String(body, Charset.forName(matcher.group(1)));
            } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
                // Fall back to UTF-8 for malformed or unsupported charset labels.
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Map<String, String> metadata(
            URI requestedUrl,
            String rawContent,
            String contentType,
            String proxyEndpoint) {
        if (!contentType.toLowerCase(Locale.ROOT).contains("html")) {
            return Map.of("proxy_route", proxyEndpoint);
        }
        Document document = Jsoup.parse(rawContent, requestedUrl.toString());
        var title = document.title().strip();
        var canonical = document.select("link[rel=canonical]").stream()
                .map(element -> element.absUrl("href"))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        var values = new java.util.HashMap<String, String>();
        values.put("proxy_route", proxyEndpoint);
        if (!title.isBlank()) {
            values.put("title", title.substring(0, Math.min(title.length(), 500)));
        }
        if (!canonical.isBlank()) {
            values.put("canonical_url", canonical);
        }
        return Map.copyOf(values);
    }

    private static URI canonicalUrl(URI requestedUrl, Map<String, String> metadata) {
        var value = metadata.get("canonical_url");
        if (value == null || value.isBlank()) {
            return requestedUrl;
        }
        try {
            var canonical = URI.create(value);
            return canonical.getHost() == null ? requestedUrl : canonical;
        } catch (IllegalArgumentException exception) {
            return requestedUrl;
        }
    }

}
