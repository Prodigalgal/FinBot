package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class SearxngSearchDiscoveryProvider implements SearchDiscoveryProvider {
    private static final int MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String USER_AGENT = "FinBot/2.0 (+https://github.com/omnnu/FinBot)";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Set<String> allowedHosts;

    SearxngSearchDiscoveryProvider(
            @Qualifier("searxngHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${FINBOT_SEARXNG_ALLOWED_HOSTS:finbot-searxng,finbot-searxng.finbot.svc,finbot-searxng.finbot.svc.cluster.local}")
                    String allowedHosts) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowedHosts = allowedHosts(allowedHosts);
    }

    @Override
    public boolean supports(String provider) {
        return "searxng_internal".equalsIgnoreCase(provider);
    }

    @Override
    public List<CollectedPayload> search(InformationSource source, String query) {
        var endpoint = requireEndpoint(source);
        var effectiveQuery = source.defaultQuery(query);
        var requestUri = requestUri(endpoint, effectiveQuery);
        var response = fetch(requestUri);
        var root = parse(response.body());
        var results = root.path("results");
        if (!results.isArray()) {
            throw failure(
                    "SEARXNG_RESPONSE_INVALID",
                    "SearXNG response did not contain a results array",
                    false,
                    response.statusCode());
        }
        var payloads = new ArrayList<CollectedPayload>();
        for (var result : results) {
            var url = safeUri(text(result, "url"));
            var content = firstText(result, "content", "description", "snippet", "title");
            if (url == null || content == null) {
                continue;
            }
            payloads.add(new CollectedPayload(
                    endpoint,
                    url,
                    effectiveQuery,
                    Objects.requireNonNullElse(text(result, "title"), url.getHost()),
                    response.statusCode(),
                    response.contentType(),
                    content,
                    Map.of("content-type", response.contentType()),
                    Map.of(
                            "collector", "searxng_search",
                            "search_provider", "searxng_internal",
                            "proxy_route", "searxng_web_crawl_proxy",
                            "source_tier", source.tier().name(),
                            "result_kind", "search_snippet",
                            "fetch_attempts", Integer.toString(response.attempts()),
                            "fetch_redirects", "0"),
                    publishedAt(result),
                    response.fetchedAt()));
            if (payloads.size() >= source.maximumResults()) {
                break;
            }
        }
        return List.copyOf(payloads);
    }

    private URI requireEndpoint(InformationSource source) {
        var endpoint = source.endpointBaseUrl();
        if (endpoint == null || endpoint.getHost() == null
                || !allowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT))
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || !"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw failure(
                    "SEARXNG_ENDPOINT_NOT_ALLOWED",
                    "SearXNG source must use an allowlisted internal HTTP service",
                    true,
                    null);
        }
        return endpoint;
    }

    private SearchResponse fetch(URI requestUri) {
        for (var attempt = 1; attempt <= 3; attempt++) {
            try {
                var request = HttpRequest.newBuilder(requestUri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.body().length > MAXIMUM_RESPONSE_BYTES) {
                    throw failure(
                            "SEARXNG_RESPONSE_TOO_LARGE",
                            "SearXNG response exceeded the configured size limit",
                            false,
                            response.statusCode());
                }
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return new SearchResponse(
                            response.statusCode(),
                            response.headers().firstValue("content-type").orElse("application/json"),
                            response.body(),
                            attempt,
                            clock.instant());
                }
                if (attempt < 3 && retryable(response.statusCode())) {
                    pause(attempt);
                    continue;
                }
                throw failure(
                        "SEARXNG_HTTP_" + response.statusCode(),
                        "SearXNG returned HTTP " + response.statusCode(),
                        response.statusCode() == 429,
                        response.statusCode());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failure("SEARXNG_INTERRUPTED", "SearXNG request was interrupted", false, null);
            } catch (IOException exception) {
                if (attempt < 3) {
                    pause(attempt);
                    continue;
                }
                throw failure(
                        "SEARXNG_NETWORK_FAILURE",
                        "SearXNG request failed after bounded retries",
                        false,
                        null);
            }
        }
        throw new IllegalStateException("SearXNG retry loop exhausted unexpectedly");
    }

    private JsonNode parse(byte[] body) {
        try {
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw failure(
                        "SEARXNG_RESPONSE_INVALID",
                        "SearXNG response root was not a JSON object",
                        false,
                        null);
            }
            return root;
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(
                    "SEARXNG_RESPONSE_INVALID",
                    "SearXNG returned invalid JSON",
                    false,
                    null);
        }
    }

    private static URI requestUri(URI endpoint, String query) {
        var separator = endpoint.getQuery() == null || endpoint.getQuery().isBlank() ? "?" : "&";
        return URI.create(endpoint + separator
                + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&pageno=1");
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(Duration.ofSeconds(attempt).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("SEARXNG_INTERRUPTED", "SearXNG retry was interrupted", false, null);
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

    private static URI safeUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            var uri = URI.create(value.strip());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return !blockedHost(host) && uri.getUserInfo() == null && uri.getFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    ? uri
                    : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean blockedHost(String host) {
        if (host.isBlank() || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.equals("::1") || host.startsWith("fc") || host.startsWith("fd")
                || host.startsWith("fe8") || host.startsWith("fe9")
                || host.startsWith("fea") || host.startsWith("feb")) {
            return true;
        }
        var octets = host.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            var first = Integer.parseInt(octets[0]);
            var second = Integer.parseInt(octets[1]);
            return first == 0 || first == 10 || first == 127 || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127
                    || first >= 224;
        } catch (NumberFormatException exception) {
            return true;
        }
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

    private static Set<String> allowedHosts(String value) {
        var hosts = new HashSet<String>();
        for (var host : Objects.requireNonNull(value, "allowedHosts").split(",")) {
            var normalized = host.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                hosts.add(normalized);
            }
        }
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("At least one SearXNG host must be allowlisted");
        }
        return Set.copyOf(hosts);
    }

    private static SourceCollectionException failure(
            String code,
            String message,
            boolean blocked,
            Integer statusCode) {
        return new SourceCollectionException(code, message, blocked, statusCode);
    }

    private record SearchResponse(
            int statusCode,
            String contentType,
            byte[] body,
            int attempts,
            Instant fetchedAt) {
        private SearchResponse {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
