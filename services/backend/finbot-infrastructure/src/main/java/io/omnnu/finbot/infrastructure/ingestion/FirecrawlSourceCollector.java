package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import io.omnnu.finbot.application.network.ProxyRouteUnavailableException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class FirecrawlSourceCollector implements SourceCollectorAdapter {
    private static final int MAXIMUM_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAXIMUM_ATTEMPTS = 3;
    private static final String USER_AGENT = "FinBot/2.0 (+https://github.com/omnnu/FinBot)";

    private final RoutedHttpClientFactory httpClients;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RuntimeSecretStore runtimeSecrets;
    private final FirecrawlChannelGuard channelGuard;

    FirecrawlSourceCollector(
            RoutedHttpClientFactory httpClients,
            ObjectMapper objectMapper,
            Clock clock,
            RuntimeSecretStore runtimeSecrets,
            FirecrawlChannelGuard channelGuard) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.channelGuard = Objects.requireNonNull(channelGuard, "channelGuard");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode.firecrawl();
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        requireProxyRoute();
        if (source.endpointBaseUrl() == null) {
            throw new SourceCollectionException(
                    "FIRECRAWL_ENDPOINT_NOT_CONFIGURED",
                    "Firecrawl source has no API base URL",
                    true);
        }
        return switch (source.mode()) {
            case FIRECRAWL_SCRAPE -> scrapeSeeds(source);
            case FIRECRAWL_SEARCH -> search(source, query, false);
            case FIRECRAWL_SEARCH_THEN_SCRAPE -> search(source, query, true);
            case RSS, HTML_DOCUMENT, SEARCH_DISCOVERY, JSON_API, SITEMAP, EXCHANGE_PUBLIC_API -> throw new IllegalStateException(
                    "Firecrawl collector received an unsupported source mode");
        };
    }

    private List<CollectedPayload> scrapeSeeds(InformationSource source) {
        if (source.seedUrls().isEmpty()) {
            throw new SourceCollectionException(
                    "FIRECRAWL_SEED_NOT_CONFIGURED",
                    "Firecrawl scrape source has no seed URL",
                    true);
        }
        var limit = Math.min(source.maximumResults(), source.seedUrls().size());
        var result = new ArrayList<CollectedPayload>();
        for (var index = 0; index < limit; index++) {
            result.add(scrape(source, source.seedUrls().get(index)));
        }
        return List.copyOf(result);
    }

    private List<CollectedPayload> search(
            InformationSource source,
            String query,
            boolean scrapeResults) {
        var effectiveQuery = source.defaultQuery(query);
        var body = objectMapper.createObjectNode()
                .put("query", effectiveQuery)
                .put("limit", source.maximumResults())
                .put("timeout", 60_000);
        body.putArray("sources").add("web").add("news");
        var response = post(source, "search", body);
        var results = searchResults(response.payload());
        if (!scrapeResults) {
            return results.stream()
                    .limit(source.maximumResults())
                    .map(result -> searchPayload(source, effectiveQuery, response, result))
                    .filter(Objects::nonNull)
                    .toList();
        }
        var urls = new LinkedHashSet<URI>();
        for (var result : results) {
            var url = resultUrl(result);
            if (url != null) {
                urls.add(url);
            }
            if (urls.size() >= source.maximumScrapeTargets()) {
                break;
            }
        }
        var payloads = new ArrayList<CollectedPayload>();
        for (var url : urls) {
            payloads.add(scrape(source, url));
        }
        return List.copyOf(payloads);
    }

    private CollectedPayload scrape(InformationSource source, URI targetUrl) {
        var body = objectMapper.createObjectNode()
                .put("url", targetUrl.toString())
                .put("onlyMainContent", true)
                .put("maxAge", 0)
                .put("timeout", 60_000);
        body.putArray("formats").add("markdown");
        var response = post(source, "scrape", body);
        var data = response.payload().path("data");
        var markdown = data.path("markdown").asText(
                response.payload().path("markdown").asText(""));
        if (markdown.isBlank()) {
            throw new SourceCollectionException(
                    "FIRECRAWL_EMPTY_CONTENT",
                    "Firecrawl scrape returned no markdown content",
                    false);
        }
        var metadata = data.path("metadata");
        var title = text(metadata, "title");
        var sourceUrl = uri(text(metadata, "sourceURL"));
        return new CollectedPayload(
                targetUrl,
                sourceUrl == null ? targetUrl : sourceUrl,
                null,
                title,
                response.statusCode(),
                response.contentType(),
                markdown,
                Map.of("content-type", response.contentType()),
                Map.of(
                        "collector", "firecrawl_scrape",
                        "proxy_route", response.proxyRoute(),
                        "source_tier", source.tier().name()),
                instant(text(metadata, "publishedTime")),
                response.fetchedAt());
    }

    private CollectedPayload searchPayload(
            InformationSource source,
            String query,
            FirecrawlResponse response,
            JsonNode result) {
        var url = resultUrl(result);
        var title = text(result, "title");
        var content = firstText(result, "markdown", "description", "snippet", "title");
        if (content == null || content.isBlank()) {
            return null;
        }
        return new CollectedPayload(
                endpoint(source, "search"),
                url,
                query,
                title,
                response.statusCode(),
                response.contentType(),
                content,
                Map.of("content-type", response.contentType()),
                Map.of(
                        "collector", "firecrawl_search",
                        "proxy_route", response.proxyRoute(),
                        "source_tier", source.tier().name()),
                null,
                response.fetchedAt());
    }

    private FirecrawlResponse post(
            InformationSource source,
            String path,
            ObjectNode body) {
        return channelGuard.execute(
                source.sourceId(),
                () -> postGuarded(source, path, body));
    }

    private FirecrawlResponse postGuarded(
            InformationSource source,
            String path,
            ObjectNode body) {
        var requestBuilder = HttpRequest.newBuilder(endpoint(source, path))
                .timeout(Duration.ofSeconds(75))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(json(body), StandardCharsets.UTF_8));
        var credential = credential(source);
        if (credential != null) {
            requestBuilder.header("Authorization", "Bearer " + credential);
        }
        try {
            for (var attempt = 1; attempt <= MAXIMUM_ATTEMPTS; attempt++) {
                try {
                    var route = httpClients.route(OutboundRoute.FIRECRAWL);
                    var response = httpClients.clientForRequest(route).send(
                            requestBuilder.build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    try (var stream = response.body()) {
                        var bytes = stream.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                        if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                            throw new SourceCollectionException(
                                    "FIRECRAWL_RESPONSE_TOO_LARGE",
                                    "Firecrawl response exceeded the configured safety limit",
                                    false);
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            if (retryable(response.statusCode()) && attempt < MAXIMUM_ATTEMPTS) {
                                Thread.sleep(retryDelay(response, attempt));
                                continue;
                            }
                            throw new SourceCollectionException(
                                    "FIRECRAWL_HTTP_" + response.statusCode(),
                                    failureMessage(response.statusCode(), bytes, attempt),
                                    response.statusCode() == 401 || response.statusCode() == 403
                                            || response.statusCode() == 429,
                                    response.statusCode());
                        }
                        var payload = objectMapper.readTree(bytes);
                        if (!payload.isObject()) {
                            throw new SourceCollectionException(
                                    "FIRECRAWL_RESPONSE_INVALID",
                                    "Firecrawl response root was not a JSON object",
                                    false);
                        }
                        return new FirecrawlResponse(
                                payload,
                                response.statusCode(),
                                response.headers().firstValue("content-type")
                                        .orElse("application/json"),
                                route.redactedEndpoint(),
                                clock.instant());
                    }
                } catch (JsonProcessingException exception) {
                    throw exception;
                } catch (IOException exception) {
                    if (attempt < MAXIMUM_ATTEMPTS) {
                        Thread.sleep(Duration.ofSeconds(attempt));
                        continue;
                    }
                    throw new SourceCollectionException(
                            "FIRECRAWL_NETWORK_FAILURE",
                            "Firecrawl request failed after " + attempt + " attempts: "
                                    + exception.getClass().getSimpleName(),
                            false);
                }
            }
            throw new IllegalStateException("Firecrawl retry loop completed without a result");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceCollectionException(
                    "FIRECRAWL_INTERRUPTED",
                    "Firecrawl request was interrupted",
                    false);
        } catch (JsonProcessingException exception) {
            throw new SourceCollectionException(
                    "FIRECRAWL_JSON_INVALID",
                    "Firecrawl returned invalid JSON",
                    false);
        }
    }

    private String failureMessage(int statusCode, byte[] responseBody, int attempts) {
        var providerCode = safeProviderCode(responseBody);
        return "Firecrawl returned HTTP " + statusCode
                + (providerCode == null ? "" : " (" + providerCode + ")")
                + " after " + attempts + " attempt" + (attempts == 1 ? "" : "s");
    }

    private String safeProviderCode(byte[] responseBody) {
        try {
            var code = objectMapper.readTree(responseBody).path("code");
            if (!code.isTextual()) {
                return null;
            }
            var value = code.textValue().strip();
            return value.matches("[A-Z0-9_]{2,80}") ? value : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 403 || statusCode == 429 || statusCode >= 500;
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        var fallbackSeconds = Math.min(4, attempt);
        var retryAfter = response.headers().firstValue("retry-after").orElse("");
        try {
            return Duration.ofSeconds(Math.max(1, Math.min(10, Long.parseLong(retryAfter))));
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(fallbackSeconds);
        }
    }

    private void requireProxyRoute() {
        try {
            var route = httpClients.route(OutboundRoute.FIRECRAWL);
            if (!route.proxied()) {
                throw new SourceCollectionException(
                        "FIRECRAWL_PROXY_REQUIRED",
                        "Firecrawl direct requests are disabled; a proxy route is required",
                        true);
            }
        } catch (ProxyRouteUnavailableException exception) {
            throw new SourceCollectionException(
                    "FIRECRAWL_PROXY_REQUIRED",
                    exception.getMessage(),
                    true);
        }
    }

    private static List<JsonNode> searchResults(JsonNode payload) {
        var data = payload.path("data");
        if (data.isArray()) {
            return array(data);
        }
        if (data.isObject()) {
            for (var field : List.of("results", "web", "news")) {
                if (data.path(field).isArray()) {
                    return array(data.path(field));
                }
            }
        }
        for (var field : List.of("results", "web", "news")) {
            if (payload.path(field).isArray()) {
                return array(payload.path(field));
            }
        }
        return List.of();
    }

    private static List<JsonNode> array(JsonNode node) {
        var result = new ArrayList<JsonNode>();
        node.forEach(result::add);
        return List.copyOf(result);
    }

    private static URI endpoint(InformationSource source, String path) {
        var base = source.endpointBaseUrl().toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + path);
    }

    private static URI resultUrl(JsonNode result) {
        return uri(firstText(result, "url", "link"));
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

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }

    private static URI uri(String value) {
        if (value == null) {
            return null;
        }
        try {
            var uri = URI.create(value);
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            return null;
        }
    }

    private String credential(InformationSource source) {
        if (source.credentialEnvironmentVariable() == null) {
            return null;
        }
        return runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        source.sourceId().value(),
                        "API_KEY",
                        source.credentialEnvironmentVariable())
                .orElseThrow(() -> new SourceCollectionException(
                        "FIRECRAWL_CREDENTIAL_MISSING",
                        "Configured Firecrawl credential is unavailable",
                        true));
    }

    private String json(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode Firecrawl request", exception);
        }
    }

    private record FirecrawlResponse(
            JsonNode payload,
            int statusCode,
            String contentType,
            String proxyRoute,
            Instant fetchedAt) {
    }
}
