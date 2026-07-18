package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.ContentEnvelopeBuilder;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class JsonSourceCollector implements SourceCollectorAdapter {
    private static final int MAXIMUM_RESPONSE_BYTES = 10 * 1024 * 1024;
    private final CrawlerTransport transport;
    private final ObjectMapper objectMapper;
    private final ContentEnvelopeBuilder envelopeBuilder;
    private final RuntimeSecretStore runtimeSecrets;

    JsonSourceCollector(
            CrawlerTransport transport,
            ObjectMapper objectMapper,
            JsonContentEnvelopeBuilder envelopeBuilder,
            RuntimeSecretStore runtimeSecrets) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.envelopeBuilder = Objects.requireNonNull(envelopeBuilder, "envelopeBuilder");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.JSON_API;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        var endpoint = source.endpointBaseUrl();
        if (endpoint == null) {
            throw new SourceCollectionException(
                    "JSON_ENDPOINT_NOT_CONFIGURED",
                    "JSON source has no endpoint URL",
                    true);
        }
        var route = source.outboundRoute() == null ? OutboundRoute.PUBLIC_DATA : source.outboundRoute();
        var requestEndpoint = credentialEndpoint(source, endpoint);
        var response = transport.get(new CrawlerTransport.Request(
                source.sourceId().value(),
                requestEndpoint,
                route,
                Map.of(
                        "Accept", "application/json, application/*+json;q=0.9",
                        "User-Agent", "FinBot/2.0 (+https://github.com/omnnu/FinBot)"),
                Duration.ofSeconds(45),
                MAXIMUM_RESPONSE_BYTES,
                3,
                route != OutboundRoute.PUBLIC_DATA,
                "JSON",
                "JSON API source"));
        var rawContent = new String(response.body(), StandardCharsets.UTF_8);
        validateJson(rawContent);
        var payload = new CollectedPayload(
                endpoint,
                endpoint,
                query,
                title(source, rawContent),
                response.statusCode(),
                response.contentType(),
                rawContent,
                response.responseHeaders(),
                Map.of(
                        "collector", "first_party_json",
                        "proxy_route", response.proxyRoute(),
                        "source_tier", source.tier().name(),
                        "fetch_attempts", Integer.toString(response.attempts()),
                        "fetch_redirects", Integer.toString(response.redirectCount())),
                null,
                response.fetchedAt());
        return List.of(payload.withEnvelope(envelopeBuilder.build(payload)));
    }

    private URI credentialEndpoint(InformationSource source, URI endpoint) {
        var provider = source.provider() == null ? "" : source.provider().strip().toLowerCase(java.util.Locale.ROOT);
        if (!provider.equals("fred") && !provider.equals("eia")) {
            return endpoint;
        }
        if (source.credentialEnvironmentVariable() == null) {
            throw new SourceCollectionException(
                    "JSON_CREDENTIAL_NOT_CONFIGURED",
                    provider.toUpperCase(java.util.Locale.ROOT) + " JSON API requires an API key binding",
                    true);
        }
        var key = runtimeSecrets.resolve(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        source.sourceId().value(),
                        "API_KEY",
                        source.credentialEnvironmentVariable())
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new SourceCollectionException(
                        "JSON_CREDENTIAL_MISSING",
                        provider.toUpperCase(java.util.Locale.ROOT) + " JSON API key is unavailable",
                        true));
        var separator = endpoint.getQuery() == null || endpoint.getQuery().isBlank() ? "?" : "&";
        return URI.create(endpoint + separator + "api_key=" + URLEncoder.encode(key.strip(), StandardCharsets.UTF_8));
    }

    private void validateJson(String rawContent) {
        try {
            JsonNode root = objectMapper.readTree(rawContent);
            if (root == null || root.isMissingNode()) {
                throw new SourceCollectionException(
                        "JSON_RESPONSE_EMPTY",
                        "JSON source returned an empty document",
                        false);
            }
        } catch (JsonProcessingException exception) {
            throw new SourceCollectionException(
                    "JSON_PARSE_FAILURE",
                    "JSON source returned an invalid document",
                    false);
        }
    }

    private String title(InformationSource source, String rawContent) {
        try {
            var root = objectMapper.readTree(rawContent);
            for (var field : List.of("title", "name", "headline")) {
                var value = root.path(field);
                if (value.isTextual() && !value.textValue().isBlank()) {
                    return value.textValue().strip().substring(0, Math.min(500, value.textValue().strip().length()));
                }
            }
        } catch (JsonProcessingException ignored) {
            // validateJson already produced the user-facing parse error.
        }
        return source.displayName();
    }
}
