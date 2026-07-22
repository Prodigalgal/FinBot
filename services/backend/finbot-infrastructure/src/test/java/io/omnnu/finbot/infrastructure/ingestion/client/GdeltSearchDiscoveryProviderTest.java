package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerPolitenessController;
import io.omnnu.finbot.infrastructure.ingestion.client.GdeltSearchDiscoveryProvider;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerConcurrencyLimiter;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.client.RoutedHttpClientFactory;
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

class GdeltSearchDiscoveryProviderTest {
    @Test
    void mapsArticleDiscoveryWithoutTreatingItAsLicensedFullText() throws Exception {
        var observedQuery = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            observedQuery.set(exchange.getRequestURI().getRawQuery());
            var body = """
                    {"articles":[{"url":"https://news.example/article","title":"Markets move",
                    "seendate":"20260718T075500Z","domain":"news.example","language":"English",
                    "sourcecountry":"United States"},{"url":"https://user:secret@news.example/private",
                    "title":"Should be discarded"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var proxy = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                    route, true, false, proxy, "IPV4", proxy.toString());
            var provider = new GdeltSearchDiscoveryProvider(
                    new CrawlerTransport(
                            new RoutedHttpClientFactory(resolver, Runnable::run),
                            new CrawlerConcurrencyLimiter(4, 2, 2, Duration.ofSeconds(1)),
                            new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                            Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                            CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.service.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()),
                    new ObjectMapper());

            var payloads = provider.search(source(), "rates");
            var payload = payloads.getFirst();

            assertEquals(1, payloads.size());
            assertEquals("gdelt_search", payload.metadata().get("collector"));
            assertEquals("https://news.example/article", payload.canonicalUrl().toString());
            assertTrue(payload.rawContent().contains("Markets move"));
            assertTrue(observedQuery.get().contains("mode=artlist"));
            assertTrue(observedQuery.get().contains("format=json"));
        } finally {
            server.stop(0);
        }
    }

    private static InformationSource source() {
        return new InformationSource(
                new SourceId("source_gdelt_test01"),
                "GDELT test",
                SourceMode.SEARCH_DISCOVERY,
                SourceTier.T2,
                "broad_market_news",
                "gdelt",
                new BigDecimal("0.7"),
                900,
                SourcePriority.P1,
                List.of("NAS100"),
                List.of(),
                List.of(),
                List.of("markets OR inflation"),
                URI.create("http://gdelt.test/api"),
                null,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                true,
                0);
    }
}
