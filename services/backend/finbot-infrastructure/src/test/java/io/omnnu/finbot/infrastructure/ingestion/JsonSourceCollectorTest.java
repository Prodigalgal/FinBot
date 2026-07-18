package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JsonSourceCollectorTest {
    @Test
    void parsesJsonAndKeepsFieldPathsAsEvidenceBlocks() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> respond(exchange, 200, "application/json",
                "{\"title\":\"Macro\",\"items\":[{\"value\":42}]}"));
        server.start();
        try {
            var collector = collector(server.getAddress().getPort());
            var payload = collector.collect(source(URI.create("http://example.test/api")), "macro").getFirst();
            assertEquals("Macro", payload.title());
            assertEquals("jackson-json-blocks-v1", payload.envelope().metadata().get("builder"));
            assertTrue(payload.envelope().blocks().stream()
                    .anyMatch(block -> "items".equals(block.attributes().get("json_path"))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMalformedJsonWithoutReturningPartialEvidence() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> respond(exchange, 200, "application/json", "{bad"));
        server.start();
        try {
            var collector = collector(server.getAddress().getPort());
            var exception = assertThrows(SourceCollectionException.class,
                    () -> collector.collect(source(URI.create("http://example.test/api")), ""));
            assertEquals("JSON_PARSE_FAILURE", exception.errorCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesSourceDisplayNameWhenStructuredPayloadHasNoTopLevelTitle() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> respond(exchange, 200, "application/json",
                "[{\"report_date\":\"2026-07-14\",\"value\":42}]"));
        server.start();
        try {
            var collector = collector(server.getAddress().getPort());
            var payload = collector.collect(source(URI.create("http://example.test/api")), "cot").getFirst();
            assertEquals("JSON test", payload.title());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsFredCredentialAsAQueryParameterWithoutPersistingItInThePayloadUrl() throws IOException {
        var observedQuery = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            observedQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "application/json", "{\"observations\":[{\"value\":\"5.25\"}]}");
        });
        server.start();
        try {
            var endpoint = URI.create("http://example.test/api?series_id=DFF&file_type=json");
            var collector = collector(server.getAddress().getPort(), new TestRuntimeSecretStore("secret-key"));

            var payload = collector.collect(source(endpoint, "fred", "FINBOT_INFORMATION_SOURCE_KEYS_JSON"), "rates")
                    .getFirst();

            assertTrue(observedQuery.get().contains("api_key=secret-key"));
            assertEquals(endpoint, payload.requestedUrl());
            assertTrue(!payload.requestedUrl().toString().contains("secret-key"));
        } finally {
            server.stop(0);
        }
    }

    private static JsonSourceCollector collector(int port) {
        return collector(port, new TestRuntimeSecretStore());
    }

    private static JsonSourceCollector collector(
            int port,
            io.omnnu.finbot.application.configuration.RuntimeSecretStore runtimeSecrets) {
        ProxyRouteResolver resolver = route -> {
            var proxy = URI.create("http://127.0.0.1:" + port);
            return new ProxyRouteDecision(route, true, false, proxy, "IPV4", proxy.toString());
        };
        return new JsonSourceCollector(
                new CrawlerTransport(
                        new RoutedHttpClientFactory(resolver, Runnable::run),
                        new CrawlerConcurrencyLimiter(16, 2, 2, Duration.ofSeconds(1)),
                        new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                        Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                        "FinBot test contact=test@example.com"),
                new ObjectMapper(),
                new JsonContentEnvelopeBuilder(new ObjectMapper()),
                runtimeSecrets);
    }

    private static InformationSource source(URI endpoint) {
        return source(endpoint, "test", null);
    }

    private static InformationSource source(
            URI endpoint,
            String provider,
            String credentialEnvironmentVariable) {
        return new InformationSource(
                new SourceId("source_json_test01"),
                "JSON test",
                SourceMode.JSON_API,
                SourceTier.T1,
                "macro",
                provider,
                new BigDecimal("0.9"),
                900,
                SourcePriority.P1,
                List.of("NAS100"),
                List.of(),
                List.of(),
                List.of(),
                endpoint,
                credentialEnvironmentVariable,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                true,
                0);
    }

    private static void respond(HttpExchange exchange, int status, String type, String content) throws IOException {
        var body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }
}
