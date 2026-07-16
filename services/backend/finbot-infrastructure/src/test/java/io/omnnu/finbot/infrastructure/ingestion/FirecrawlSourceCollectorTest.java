package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FirecrawlSourceCollectorTest {
    @Test
    void retriesForbiddenResponseWithANewProxyRequestAndReturnsEvidence() throws IOException {
        var requests = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> respond(exchange, requests.incrementAndGet()));
        proxy.start();
        try {
            var proxyUri = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
            ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                    route,
                    true,
                    false,
                    proxyUri,
                    "IPV4",
                    proxyUri.toString());
            var collector = new FirecrawlSourceCollector(
                    new RoutedHttpClientFactory(resolver, Runnable::run),
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-07-16T14:00:00Z"), ZoneOffset.UTC),
                    new EmptyRuntimeSecretStore());

            var payloads = collector.collect(source(), "market update");

            assertEquals(2, requests.get());
            assertEquals(1, payloads.size());
            assertEquals("# verified", payloads.getFirst().rawContent());
        } finally {
            proxy.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int requestNumber) throws IOException {
        try (exchange) {
            if (requestNumber == 1) {
                var body = "{\"code\":\"IP_BLOCKED\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(403, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            var body = """
                    {"data":{"markdown":"# verified","metadata":{"title":"Verified","sourceURL":"https://example.com/article"}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static InformationSource source() {
        return new InformationSource(
                new SourceId("source_firecrawl_test01"),
                "Firecrawl test",
                SourceMode.FIRECRAWL_SCRAPE,
                SourceTier.T2,
                "market_news",
                "firecrawl",
                new BigDecimal("0.8"),
                900,
                SourcePriority.P2,
                List.of("BTCUSDT"),
                List.of(),
                List.of(URI.create("https://example.com/article")),
                List.of(),
                URI.create("http://api.firecrawl.test/v2"),
                null,
                OutboundRoute.FIRECRAWL,
                10,
                1,
                true,
                0);
    }

    private static final class EmptyRuntimeSecretStore implements RuntimeSecretStore {
        @Override
        public Optional<String> resolve(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return Optional.empty();
        }

        @Override
        public RuntimeSecretStatus status(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RuntimeSecretStatus> put(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String value,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RuntimeSecretStatus> clear(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
