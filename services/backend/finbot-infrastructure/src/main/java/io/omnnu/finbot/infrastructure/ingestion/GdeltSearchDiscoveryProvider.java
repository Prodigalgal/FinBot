package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** GDELT DOC 2.0 article discovery; it returns URLs and metadata, not licensed full text. */
@Component
final class GdeltSearchDiscoveryProvider implements SearchDiscoveryProvider {
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final DateTimeFormatter GDELT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private final CrawlerTransport transport;
    private final ObjectMapper objectMapper;

    GdeltSearchDiscoveryProvider(CrawlerTransport transport, ObjectMapper objectMapper) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public boolean supports(String provider) {
        return "gdelt".equalsIgnoreCase(provider);
    }

    @Override
    public List<CollectedPayload> search(InformationSource source, String query) {
        var endpoint = source.endpointBaseUrl();
        if (endpoint == null) {
            throw new SourceCollectionException(
                    "SEARCH_DISCOVERY_ENDPOINT_NOT_CONFIGURED",
                    "GDELT source has no search endpoint",
                    true);
        }
        var effectiveQuery = source.defaultQuery(query);
        var requestUri = requestUri(endpoint, effectiveQuery, source.maximumResults());
        var route = source.outboundRoute() == null ? OutboundRoute.PUBLIC_DATA : source.outboundRoute();
        var response = transport.get(new CrawlerTransport.Request(
                source.sourceId().value(),
                requestUri,
                route,
                Map.of(
                        "Accept", "application/json",
                        "User-Agent", "FinBot/2.0 (+https://github.com/Prodigalgal/FinBot)"),
                REQUEST_TIMEOUT,
                MAXIMUM_RESPONSE_BYTES,
                3,
                route != OutboundRoute.PUBLIC_DATA,
                "GDELT",
                "GDELT article discovery"));
        var root = parse(response.body());
        var payloads = new ArrayList<CollectedPayload>();
        var articles = root.path("articles");
        if (!articles.isArray()) {
            throw new SourceCollectionException(
                    "GDELT_RESPONSE_INVALID",
                    "GDELT response did not contain an articles array",
                    false);
        }
        for (var article : articles) {
            var url = httpUrl(firstText(article, "url", "urlmobile"));
            var title = text(article, "title");
            if (url == null || title == null || title.isBlank()) {
                continue;
            }
            var content = title
                    + "\n来源域名：" + Objects.requireNonNullElse(text(article, "domain"), "未知")
                    + "\n语言：" + Objects.requireNonNullElse(text(article, "language"), "未知")
                    + "\n来源国家：" + Objects.requireNonNullElse(text(article, "sourcecountry"), "未知");
            payloads.add(new CollectedPayload(
                    endpoint,
                    url,
                    effectiveQuery,
                    title,
                    response.statusCode(),
                    response.contentType(),
                    content,
                    response.responseHeaders(),
                    Map.of(
                            "collector", "gdelt_search",
                            "search_provider", "gdelt",
                            "proxy_route", response.proxyRoute(),
                            "source_tier", source.tier().name(),
                            "result_kind", "news_url_discovery",
                            "fetch_attempts", Integer.toString(response.attempts()),
                            "fetch_redirects", Integer.toString(response.redirectCount())),
                    publishedAt(article),
                    response.fetchedAt()));
            if (payloads.size() >= source.maximumResults()) {
                break;
            }
        }
        return List.copyOf(payloads);
    }

    private static URI requestUri(URI endpoint, String query, int maximumResults) {
        var separator = endpoint.getQuery() == null || endpoint.getQuery().isBlank() ? "?" : "&";
        return URI.create(endpoint + separator
                + "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&mode=artlist&maxrecords=" + maximumResults
                + "&format=json&sort=HybridRel");
    }

    private JsonNode parse(byte[] body) {
        try {
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new SourceCollectionException(
                        "GDELT_RESPONSE_INVALID",
                        "GDELT response root was not an object",
                        false);
            }
            return root;
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (java.io.IOException exception) {
            throw new SourceCollectionException(
                    "GDELT_JSON_INVALID",
                    "GDELT returned invalid JSON",
                    false);
        }
    }

    private static URI httpUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            var uri = URI.create(value.strip());
            return uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    ? uri
                    : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant publishedAt(JsonNode article) {
        var value = text(article, "seendate");
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, GDELT_DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
