package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SitemapSourceCollectorTest {
    @Test
    void parsesLocationsWithExternalEntitiesDisabled() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sitemap.xml", exchange -> respond(exchange, """
                <?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://example.com/a</loc></url>
                  <url><loc>https://example.com/b</loc></url>
                  <url><loc>file:///etc/passwd</loc></url>
                </urlset>
                """));
        server.start();
        try {
            var collector = collector(server.getAddress().getPort());
            var payload = collector.collect(source(URI.create("http://example.test/sitemap.xml")), "").getFirst();
            assertEquals(2, payload.envelope().blocks().size());
            assertTrue(payload.envelope().normalizedText().contains("https://example.com/a"));
            assertTrue(payload.envelope().normalizedText().contains("https://example.com/b"));
        } finally {
            server.stop(0);
        }
    }

    private static SitemapSourceCollector collector(int port) {
        ProxyRouteResolver resolver = route -> {
            var proxy = URI.create("http://127.0.0.1:" + port);
            return new ProxyRouteDecision(route, true, false, proxy, "IPV4", proxy.toString());
        };
        return new SitemapSourceCollector(new CrawlerTransport(
                new RoutedHttpClientFactory(resolver, Runnable::run),
                new CrawlerConcurrencyLimiter(16, 2, 2, Duration.ofSeconds(1)),
                new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()));
    }

    private static InformationSource source(URI endpoint) {
        return new InformationSource(
                new SourceId("source_sitemap_test01"), "Sitemap test", SourceMode.SITEMAP,
                SourceTier.T1, "macro", "test", new BigDecimal("0.9"), 900,
                SourcePriority.P1, List.of("NAS100"), List.of(), List.of(), List.of(), endpoint,
                null, OutboundRoute.WEB_CRAWL, 10, 0, true, 0);
    }

    private static void respond(HttpExchange exchange, String content) throws IOException {
        var body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }
}
