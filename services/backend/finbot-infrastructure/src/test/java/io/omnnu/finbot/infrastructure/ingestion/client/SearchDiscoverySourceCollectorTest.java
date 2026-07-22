package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerPolitenessController;
import io.omnnu.finbot.infrastructure.ingestion.client.JsonSearchDiscoveryProvider;
import io.omnnu.finbot.infrastructure.ingestion.client.SearchDiscoverySourceCollector;
import io.omnnu.finbot.infrastructure.ingestion.client.SearchResultQualityGate;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerConcurrencyLimiter;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.client.RoutedHttpClientFactory;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearchDiscoverySourceCollectorTest {
    @Test
    void readsSearxCompatibleResultsThroughTheWebCrawlProxy() throws IOException {
        var observedTarget = new AtomicReference<String>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/search", exchange -> respond(exchange, observedTarget));
        proxy.start();
        try {
            var proxyUri = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
            var collector = collector(proxyUri);
            var payloads = collector.collect(source(proxyUri), "oil inventory");

            assertEquals(1, payloads.size());
            assertEquals("Oil inventory", payloads.getFirst().title());
            assertEquals("https://example.com/oil", payloads.getFirst().canonicalUrl().toString());
            assertEquals("search_discovery", payloads.getFirst().metadata().get("collector"));
            assertEquals("accepted", payloads.getFirst().metadata().get("search_quality_gate"));
            assertEquals("1", payloads.getFirst().metadata().get("search_quality_candidate_count"));
            assertTrue(observedTarget.get().contains("q=oil"));
            assertTrue(observedTarget.get().contains("inventory"));
            assertTrue(observedTarget.get().contains("format=json"));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void failsClosedWhenSearchEndpointIsMissing() {
        var collector = collector(URI.create("http://127.0.0.1:1"));

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> collector.collect(source(null), "oil inventory"));

        assertEquals("SEARCH_DISCOVERY_ENDPOINT_NOT_CONFIGURED", exception.errorCode());
        assertTrue(exception.blocked());
    }

    @Test
    void bindsBraveCredentialToTheSourceInsteadOfTheModelOrRoute() throws IOException {
        var observedCredential = new AtomicReference<String>();
        var observedTarget = new AtomicReference<String>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/search", exchange -> {
            observedCredential.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            respond(exchange, observedTarget);
        });
        proxy.start();
        try {
            var proxyUri = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
            var collector = collector(proxyUri, new StaticRuntimeSecretStore());

            var payloads = collector.collect(braveSource(), "oil inventory");

            assertEquals(1, payloads.size());
            assertEquals("brave-test-key", observedCredential.get());
            assertTrue(observedTarget.get().contains("count=10"));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void rejectsAnUnregisteredSearchProviderBeforeAnyNetworkRequest() {
        var collector = collector(URI.create("http://127.0.0.1:1"));

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> collector.collect(
                        source("unknown-search", URI.create("http://search.test/search"), null),
                        "oil inventory"));

        assertEquals("SEARCH_DISCOVERY_PROVIDER_UNSUPPORTED", exception.errorCode());
        assertTrue(exception.blocked());
    }

    private static SearchDiscoverySourceCollector collector(URI proxyUri) {
        return collector(proxyUri, new EmptyRuntimeSecretStore());
    }

    private static SearchDiscoverySourceCollector collector(
            URI proxyUri,
            RuntimeSecretStore runtimeSecrets) {
        ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                route,
                true,
                false,
                proxyUri,
                "IPV4",
                proxyUri.toString());
        return new SearchDiscoverySourceCollector(
                List.of(new JsonSearchDiscoveryProvider(
                        new CrawlerTransport(
                                new RoutedHttpClientFactory(resolver, Runnable::run),
                                new CrawlerConcurrencyLimiter(16, 2, 2, Duration.ofSeconds(1)),
                                new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                                CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.service.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()),
                        new ObjectMapper(),
                        runtimeSecrets)),
                new SearchResultQualityGate());
    }

    private static void respond(HttpExchange exchange, AtomicReference<String> observedTarget)
            throws IOException {
        observedTarget.set(exchange.getRequestURI().toString());
        var body = """
                {"results":[{"title":"Oil inventory","url":"https://example.com/oil",
                "content":"Official inventory fell this week.","publishedDate":"2026-07-18T07:00:00Z"},
                {"title":"Credential URL","url":"https://user:secret@example.com/private","content":"discard"}]}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static InformationSource source(URI proxyUri) {
        return source(
                "searxng",
                proxyUri == null ? null : URI.create("http://search.test/search"),
                null);
    }

    private static InformationSource braveSource() {
        return source(
                "brave",
                URI.create("http://search.test/search"),
                "FINBOT_INFORMATION_SOURCE_KEYS_JSON");
    }

    private static InformationSource source(
            String provider,
            URI endpoint,
            String credentialEnvironmentVariable) {
        return new InformationSource(
                new SourceId("source_search_test01"),
                "Search test",
                SourceMode.SEARCH_DISCOVERY,
                SourceTier.T2,
                "market_news",
                provider,
                new BigDecimal("0.8"),
                900,
                SourcePriority.P2,
                List.of("USOIL"),
                List.of(),
                List.of(),
                List.of("oil"),
                endpoint,
                credentialEnvironmentVariable,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                true,
                0);
    }

    private static class EmptyRuntimeSecretStore implements RuntimeSecretStore {
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

    private static final class StaticRuntimeSecretStore extends EmptyRuntimeSecretStore {
        @Override
        public Optional<String> resolve(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            assertEquals(RuntimeSecretScope.INFORMATION_SOURCE, scope);
            assertEquals("source_search_test01", targetId);
            assertEquals("API_KEY", secretName);
            return Optional.of("brave-test-key");
        }
    }
}
