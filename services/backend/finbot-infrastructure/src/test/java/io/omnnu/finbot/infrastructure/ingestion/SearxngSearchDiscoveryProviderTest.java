package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearxngSearchDiscoveryProviderTest {
    @Test
    void queriesAllowlistedInternalServiceAndMapsResults() throws Exception {
        var observedQuery = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            observedQuery.set(exchange.getRequestURI().getRawQuery());
            var body = """
                    {"results":[
                      {"url":"https://news.example/health","title":"Health update",
                       "content":"Official health evidence","publishedDate":"2026-07-18T07:00:00Z"},
                      {"url":"javascript:alert(1)","title":"Discarded","content":"Unsafe"}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(server.getAddress()))
                    .build();
            var provider = new SearxngSearchDiscoveryProvider(
                    client,
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                    "searxng.test");

            var payloads = provider.search(source("searxng.test"), "healthcare");

            assertEquals(1, payloads.size());
            assertEquals("searxng_search", payloads.getFirst().metadata().get("collector"));
            assertEquals("https://news.example/health", payloads.getFirst().canonicalUrl().toString());
            assertTrue(observedQuery.get().contains("format=json"));
            assertTrue(observedQuery.get().contains("q=global+news"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonAllowlistedInternalEndpointBeforeNetworkAccess() {
        var provider = new SearxngSearchDiscoveryProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.systemUTC(),
                "finbot-searxng");

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source("untrusted.example"), "news"));

        assertEquals("SEARXNG_ENDPOINT_NOT_ALLOWED", exception.errorCode());
    }

    private static InformationSource source(String host) {
        return new InformationSource(
                new SourceId("source_searxng_test"),
                "SearXNG test",
                SourceMode.SEARCH_DISCOVERY,
                SourceTier.T4,
                "search_discovery",
                "searxng_internal",
                new BigDecimal("0.5"),
                900,
                SourcePriority.P2,
                List.of(),
                List.of(),
                List.of(),
                List.of("global news"),
                URI.create("http://" + host + "/search?categories=news"),
                null,
                OutboundRoute.PUBLIC_DATA,
                10,
                0,
                true,
                0);
    }
}
