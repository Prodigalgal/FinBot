package io.omnnu.finbot.infrastructure.configuration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.configuration.dto.CapabilitySupport;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkProviderModelCatalogGatewayTest {
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void requestsStandardModelsEndpointAndReturnsStructuredCapabilities() throws Exception {
        var authorization = new AtomicReference<String>();
        var server = server(200, """
                {"data":[{"id":"model-a","supported_protocols":["chat"],"supports_streaming":true}]}
                """, authorization);
        try {
            var result = gateway().probe(
                    "provider_test",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "secret-key",
                    Duration.ofSeconds(5));

            assertEquals("READY", result.status());
            assertEquals(java.util.List.of("model-a"), result.models());
            assertEquals(1, result.modelCapabilities().size());
            assertEquals(java.util.Set.of(AiProtocol.CHAT), result.modelCapabilities().getFirst().supportedProtocols());
            assertEquals(CapabilitySupport.SUPPORTED, result.modelCapabilities().getFirst().streaming());
            assertTrue(result.warnings().isEmpty());
            assertEquals("Bearer secret-key", authorization.get());
            assertEquals(NOW, result.checkedAt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesFailedResultContractForHttpAndMalformedJsonResponses() throws Exception {
        var authorization = new AtomicReference<String>();
        var unavailable = server(503, "upstream unavailable", authorization);
        try {
            var result = gateway().probe(
                    "provider_test",
                    URI.create("http://127.0.0.1:" + unavailable.getAddress().getPort() + "/v1/"),
                    "secret-key",
                    Duration.ofSeconds(5));

            assertEquals("FAILED", result.status());
            assertEquals("MODEL_CATALOG_HTTP_STATUS", result.errorCode());
            assertTrue(result.models().isEmpty());
            assertTrue(result.modelCapabilities().isEmpty());
            assertTrue(result.warnings().isEmpty());
        } finally {
            unavailable.stop(0);
        }

        var malformed = server(200, "not-json", authorization);
        try {
            var result = gateway().probe(
                    "provider_test",
                    URI.create("http://127.0.0.1:" + malformed.getAddress().getPort() + "/v1"),
                    "secret-key",
                    Duration.ofSeconds(5));

            assertEquals("FAILED", result.status());
            assertEquals("MODEL_CATALOG_PROBE_FAILED", result.errorCode());
            assertTrue(result.models().isEmpty());
        } finally {
            malformed.stop(0);
        }
    }

    private static JdkProviderModelCatalogGateway gateway() {
        return new JdkProviderModelCatalogGateway(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HttpServer server(
            int status,
            String responseBody,
            AtomicReference<String> authorization) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, status, responseBody, authorization));
        server.start();
        return server;
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String responseBody,
            AtomicReference<String> authorization) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
