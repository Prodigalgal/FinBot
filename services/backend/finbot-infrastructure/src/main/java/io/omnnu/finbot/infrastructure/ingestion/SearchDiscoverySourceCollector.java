package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class SearchDiscoverySourceCollector implements SourceCollectorAdapter {
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final String USER_AGENT = "FinBot/2.0 (+https://github.com/omnnu/FinBot)";

    private final CrawlerTransport transport;
    private final ObjectMapper objectMapper;
    private final RuntimeSecretStore runtimeSecrets;

    SearchDiscoverySourceCollector(
            CrawlerTransport transport,
            ObjectMapper objectMapper,
            RuntimeSecretStore runtimeSecrets) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.SEARCH_DISCOVERY;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        var endpoint = source.endpointBaseUrl();
        if (endpoint == null) {
            throw new SourceCollectionException(
                    "SEARCH_DISCOVERY_ENDPOINT_NOT_CONFIGURED",
                    "Search discovery source has no search endpoint",
                    true);
        }
        var effectiveQuery = source.defaultQuery(query);
        var response = fetch(source, endpoint, effectiveQuery);
        return parseResults(source, endpoint, effectiveQuery, response);
    }

    private SearchResponse fetch(
            InformationSource source,
            URI endpoint,
            String query) {
        var provider = provider(source);
        var credential = credential(source, provider);
        var requestUri = requestUri(endpoint, query, source.maximumResults(), provider);
        var headers = new java.util.HashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        if (credential != null) {
            headers.put("X-Subscription-Token", credential);
            headers.put("Authorization", "Bearer " + credential);
        }
        var response = transport.get(new CrawlerTransport.Request(
                requestUri,
                OutboundRoute.WEB_CRAWL,
                headers,
                REQUEST_TIMEOUT,
                MAXIMUM_RESPONSE_BYTES,
                3,
                true,
                "SEARCH_DISCOVERY",
                "Search discovery"));
        var contentType = "application/octet-stream".equals(response.contentType())
                ? "application/json"
                : response.contentType();
        return new SearchResponse(
                parseJson(response.body()),
                response.statusCode(),
                contentType,
                response.proxyRoute(),
                response.fetchedAt());
    }

    private List<CollectedPayload> parseResults(
            InformationSource source,
            URI endpoint,
            String query,
            SearchResponse response) {
        var results = resultArray(response.payload());
        var payloads = new ArrayList<CollectedPayload>();
        for (var result : results) {
            var url = resultUrl(result);
            var content = firstText(result, "content", "description", "snippet", "body", "title");
            if (url == null || content == null || content.isBlank()) {
                continue;
            }
            payloads.add(new CollectedPayload(
                    endpoint,
                    url,
                    query,
                    firstText(result, "title", "name"),
                    response.statusCode(),
                    response.contentType(),
                    content,
                    Map.of("content-type", response.contentType()),
                    Map.of(
                            "collector", "search_discovery",
                            "search_provider", provider(source),
                            "proxy_route", response.proxyRoute(),
                            "source_tier", source.tier().name(),
                            "result_kind", "search_snippet"),
                    publishedAt(result),
                    response.fetchedAt()));
            if (payloads.size() >= source.maximumResults()) {
                break;
            }
        }
        return List.copyOf(payloads);
    }

    private String credential(InformationSource source, String provider) {
        if (!"brave".equals(provider)) {
            return null;
        }
        if (source.credentialEnvironmentVariable() == null) {
            throw new SourceCollectionException(
                    "SEARCH_DISCOVERY_CREDENTIAL_NOT_CONFIGURED",
                    "Brave search requires an API key binding",
                    true);
        }
        return runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        source.sourceId().value(),
                        "API_KEY",
                        source.credentialEnvironmentVariable())
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new SourceCollectionException(
                        "SEARCH_DISCOVERY_CREDENTIAL_MISSING",
                        "Configured search API key is unavailable",
                        true));
    }

    private static String provider(InformationSource source) {
        var provider = source.provider();
        return provider == null || provider.isBlank() ? "generic" : provider.toLowerCase(Locale.ROOT);
    }

    private static URI requestUri(URI endpoint, String query, int maximumResults, String provider) {
        var separator = endpoint.getQuery() == null || endpoint.getQuery().isBlank() ? "?" : "&";
        var parameters = new ArrayList<String>();
        parameters.add("q=" + encode(query));
        if ("brave".equals(provider)) {
            parameters.add("count=" + maximumResults);
        } else {
            parameters.add("format=json");
            parameters.add("pageno=1");
        }
        return URI.create(endpoint + separator + String.join("&", parameters));
    }

    private static List<JsonNode> resultArray(JsonNode payload) {
        for (var candidate : List.of(
                payload.path("results"),
                payload.path("web").path("results"),
                payload.path("data").path("results"),
                payload.path("data").path("web"))) {
            if (candidate.isArray()) {
                var results = new ArrayList<JsonNode>();
                candidate.forEach(results::add);
                return List.copyOf(results);
            }
        }
        return List.of();
    }

    private static URI resultUrl(JsonNode result) {
        var value = firstText(result, "url", "link", "href");
        if (value == null) {
            return null;
        }
        try {
            var uri = URI.create(value);
            return isHttp(uri) ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.path(field);
            if (value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue().strip();
            }
        }
        return null;
    }

    private static Instant publishedAt(JsonNode result) {
        var value = firstText(result, "publishedDate", "published", "date");
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private JsonNode parseJson(byte[] body) {
        final JsonNode payload;
        try {
            payload = objectMapper.readTree(body);
        } catch (java.io.IOException exception) {
            throw new SourceCollectionException(
                    "SEARCH_DISCOVERY_JSON_INVALID",
                    "Search discovery endpoint returned invalid JSON",
                    false);
        }
        if (payload == null || !payload.isObject()) {
            throw new SourceCollectionException(
                    "SEARCH_DISCOVERY_JSON_INVALID",
                    "Search discovery response root was not a JSON object",
                    false);
        }
        return payload;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isHttp(URI uri) {
        return uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private record SearchResponse(
            JsonNode payload,
            int statusCode,
            String contentType,
            String proxyRoute,
            Instant fetchedAt) {
    }
}
