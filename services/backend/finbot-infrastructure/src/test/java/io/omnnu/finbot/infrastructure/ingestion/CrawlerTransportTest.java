package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CrawlerTransportTest {
    @Test
    void blocksTargetsThatResolveToLoopbackBeforeOpeningTheRoute() {
        var transport = new CrawlerTransport(
                new RoutedHttpClientFactory(
                        route -> new ProxyRouteDecision(
                                route, true, false, URI.create("http://127.0.0.1:1"), "IPV4", "redacted"),
                        Runnable::run),
                new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(1)),
                new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                Clock.systemUTC(),
                "FinBot test contact=test@example.com");

        var exception = assertThrows(SourceCollectionException.class, () -> transport.get(
                new CrawlerTransport.Request(
                        "source_test_crawler",
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
                    new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(1)),
                    new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                    Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                    "FinBot test contact=test@example.com");

            var response = transport.get(new CrawlerTransport.Request(
                    "source_test_crawler",
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

    @Test
    void followsBoundedGetRedirectsAndReportsNetworkAttempts() throws IOException {
        var requests = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            requests.incrementAndGet();
            if ("/start".equals(exchange.getRequestURI().getPath())) {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location", "http://target.test/final");
                    exchange.sendResponseHeaders(302, -1);
                }
                return;
            }
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var transport = transportThrough(proxy);

            var response = transport.get(request(URI.create("http://target.test/start"), 1));

            assertEquals(2, requests.get());
            assertEquals(2, response.attempts());
            assertEquals(1, response.redirectCount());
            assertEquals("http://target.test/final", response.requestedUrl().toString());
            assertFalse(response.responseHeaders().containsKey("location"));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void preservesUpstreamHttpStatusInBlockedCollectionErrors() throws IOException {
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/blocked", exchange -> {
            try (exchange) {
                var body = "blocked".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(403, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var exception = assertThrows(SourceCollectionException.class,
                    () -> transportThrough(proxy).get(request(URI.create("http://target.test/blocked"), 1)));

            assertEquals("TEST_CRAWLER_HTTP_403", exception.errorCode());
            assertEquals(403, exception.statusCode());
            assertTrue(exception.blocked());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void waitsAtLeastFiveSecondsWhenRateLimitedWithoutRetryAfter() {
        var response = new CrawlerTransport.Response(
                URI.create("https://api.gdeltproject.org/api/v2/doc/doc"),
                429,
                "text/plain",
                new byte[0],
                Map.of(),
                null,
                "direct",
                1,
                0,
                Instant.parse("2026-07-18T08:00:00Z"));

        assertEquals(Duration.ofSeconds(5), CrawlerTransport.retryDelay(response, 1));
    }

    @Test
    void blocksRedirectsToPrivateTargetsBeforeOpeningAnotherConnection() throws IOException {
        var requests = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1/internal");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        proxy.start();
        try {
            var transport = transportThrough(proxy);

            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> transport.get(request(URI.create("http://target.test/start"), 1)));

            assertEquals("TEST_CRAWLER_SSRF_BLOCKED", exception.errorCode());
            assertEquals(1, requests.get());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void removesCredentialsWhenRedirectingToAnotherOrigin() throws IOException {
        var finalAuthorization = new AtomicReference<String>();
        var finalSubscriptionToken = new AtomicReference<String>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            if ("/start".equals(exchange.getRequestURI().getPath())) {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location", "http://other.test/final");
                    exchange.sendResponseHeaders(302, -1);
                }
                return;
            }
            finalAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            finalSubscriptionToken.set(exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var request = new CrawlerTransport.Request(
                    "source_test_crawler",
                    URI.create("http://target.test/start"),
                    OutboundRoute.WEB_CRAWL,
                    Map.of(
                            "Accept", "text/plain",
                            "Authorization", "Bearer secret",
                            "X-Subscription-Token", "secret-token"),
                    Duration.ofSeconds(5),
                    1_024,
                    1,
                    true,
                    "TEST_CRAWLER",
                    "Test crawler");

            transportThrough(proxy).get(request);

            assertNull(finalAuthorization.get());
            assertNull(finalSubscriptionToken.get());
        } finally {
            proxy.stop(0);
        }
    }

    private static CrawlerTransport transportThrough(HttpServer proxy) {
        var proxyUri = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
        ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                route, true, false, proxyUri, "IPV4", proxyUri.toString());
        return new CrawlerTransport(
                new RoutedHttpClientFactory(resolver, Runnable::run),
                new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(1)),
                new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                "FinBot test contact=test@example.com");
    }

    private static CrawlerTransport.Request request(URI target, int maximumAttempts) {
        return new CrawlerTransport.Request(
                "source_test_crawler",
                target,
                OutboundRoute.WEB_CRAWL,
                Map.of("Accept", "text/plain"),
                Duration.ofSeconds(5),
                1_024,
                maximumAttempts,
                true,
                "TEST_CRAWLER",
                "Test crawler");
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
