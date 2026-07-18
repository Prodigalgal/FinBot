package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CrawlerTransportTest {
    @Test
    void blocksTargetsThatResolveToLoopbackBeforeOpeningTheRoute() {
        var transport = new CrawlerTransport(
                new RoutedHttpClientFactory(
                        route -> new ProxyRouteDecision(
                                route, true, false, URI.create("http://127.0.0.1:1"), "IPV4", "redacted"),
                        Runnable::run),
                new CrawlerConcurrencyLimiter(2, 1, Duration.ofSeconds(1)),
                Clock.systemUTC());

        var exception = assertThrows(SourceCollectionException.class, () -> transport.get(
                new CrawlerTransport.Request(
                        URI.create("http://127.0.0.1/metadata"),
                        OutboundRoute.WEB_CRAWL,
                        Map.of(),
                        Duration.ofSeconds(5),
                        1_024,
                        1,
                        true,
                        "TEST_CRAWLER",
                        "Test crawler")));

        assertEquals("TEST_CRAWLER_SSRF_BLOCKED", exception.errorCode());
    }

    @Test
    void retriesWithANewRouteDecisionAndKeepsOnlySafeHeaders() throws IOException {
        var routeCalls = new AtomicInteger();
        var requests = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/data", exchange -> respond(exchange, requests.incrementAndGet()));
        proxy.start();
        try {
            var proxyUri = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
            ProxyRouteResolver resolver = route -> {
                routeCalls.incrementAndGet();
                return new ProxyRouteDecision(route, true, false, proxyUri, "IPV4", proxyUri.toString());
            };
            var transport = new CrawlerTransport(
                    new RoutedHttpClientFactory(resolver, Runnable::run),
                    new CrawlerConcurrencyLimiter(2, 1, Duration.ofSeconds(1)),
                    Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC));

            var response = transport.get(new CrawlerTransport.Request(
                    URI.create("http://target.test/data"),
                    OutboundRoute.WEB_CRAWL,
                    Map.of("Accept", "application/json"),
                    Duration.ofSeconds(5),
                    1_024,
                    2,
                    true,
                    "TEST_CRAWLER",
                    "Test crawler"));

            assertEquals(2, routeCalls.get());
            assertEquals(2, requests.get());
            assertEquals(2, response.attempts());
            assertEquals("application/json", response.responseHeaders().get("content-type"));
            assertFalse(response.responseHeaders().containsKey("set-cookie"));
        } finally {
            proxy.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int requestNumber) throws IOException {
        var status = requestNumber == 1 ? 503 : 200;
        var body = (status == 200 ? "{\"ok\":true}" : "busy").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Set-Cookie", "secret=value");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }
}
