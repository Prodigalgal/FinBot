package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.ContentEnvelopeBuilder;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
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

    JsonSourceCollector(
            CrawlerTransport transport,
            ObjectMapper objectMapper,
            JsonContentEnvelopeBuilder envelopeBuilder) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.envelopeBuilder = Objects.requireNonNull(envelopeBuilder, "envelopeBuilder");
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
        var response = transport.get(new CrawlerTransport.Request(
                endpoint,
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
                title(rawContent),
                response.statusCode(),
                response.contentType(),
                rawContent,
                response.responseHeaders(),
                Map.of(
                        "collector", "first_party_json",
                        "proxy_route", response.proxyRoute(),
                        "source_tier", source.tier().name(),
                        "fetch_attempts", Integer.toString(response.attempts())),
                null,
                response.fetchedAt());
        return List.of(payload.withEnvelope(envelopeBuilder.build(payload)));
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

    private String title(String rawContent) {
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
        return null;
    }
}
