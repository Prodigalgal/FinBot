package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RssSourceCollectorTest {
    @Test
    void resolvesAProxyForEachFeedRequest() throws IOException {
        var routeCalls = new AtomicInteger();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.xml", exchange -> respond(exchange, requests));
        server.start();
        try {
            var proxyUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ProxyRouteResolver resolver = route -> {
                routeCalls.incrementAndGet();
                return new ProxyRouteDecision(route, true, false, proxyUri, "IPV4", proxyUri.toString());
            };
            var collector = new RssSourceCollector(
                    new CrawlerTransport(
                            new RoutedHttpClientFactory(resolver, Runnable::run),
                            new CrawlerConcurrencyLimiter(16, 2, 2, Duration.ofSeconds(1)),
                            new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                            Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                            CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()));

            var payloads = collector.collect(source(), "");

            assertEquals(2, routeCalls.get());
            assertEquals(2, requests.get());
            assertEquals(2, payloads.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotDirectConnectWhenTheSourceUsesTheWebCrawlRoute() throws IOException {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.xml", exchange -> respond(exchange, requests));
        server.start();
        try {
            ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                    route,
                    false,
                    true,
                    null,
                    "IPV4",
                    "direct");
            var collector = new RssSourceCollector(
                    new CrawlerTransport(
                            new RoutedHttpClientFactory(resolver, Runnable::run),
                            new CrawlerConcurrencyLimiter(16, 2, 2, Duration.ofSeconds(1)),
                            new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                            Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                            CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()));

            var exception = assertThrows(SourceCollectionException.class,
                    () -> collector.collect(source(OutboundRoute.WEB_CRAWL, server.getAddress().getPort()), ""));

            assertEquals("RSS_PROXY_REQUIRED", exception.errorCode());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, AtomicInteger requests) throws IOException {
        requests.incrementAndGet();
        var body = """
                <?xml version="1.0"?><rss version="2.0"><channel>
                <item><title>Market update</title><link>https://example.com/update</link>
                <description>Inventory changed this week.</description></item>
                </channel></rss>
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
        exchange.sendResponseHeaders(200, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static InformationSource source() {
        return source(OutboundRoute.PUBLIC_DATA, -1);
    }

    private static InformationSource source(OutboundRoute route, int port) {
        var feed = port < 0 ? URI.create("http://feed.test/feed.xml")
                : URI.create("http://feed.test:" + port + "/feed.xml");
        return new InformationSource(
                new SourceId("source_rss_test01"),
                "RSS test",
                SourceMode.RSS,
                SourceTier.T1,
                "market_news",
                "rss",
                new BigDecimal("0.8"),
                900,
                SourcePriority.P2,
                List.of("USOIL"),
                List.of(feed, feed),
                List.of(),
                List.of(),
                null,
                null,
                route,
                10,
                0,
                true,
                0);
    }

}
