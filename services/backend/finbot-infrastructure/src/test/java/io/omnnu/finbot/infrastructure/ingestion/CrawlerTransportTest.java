package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypass;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CrawlerTransportTest {
    @Test
    void appliesProfileHeadersIncludingCallerCamouflageOverrides() throws IOException {
        var observedUserAgent = new AtomicReference<String>();
        var observedAccept = new AtomicReference<String>();
        var observedLanguage = new AtomicReference<String>();
        var observedForwarded = new AtomicReference<String>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/headers", exchange -> {
            observedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            observedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            observedLanguage.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            observedForwarded.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            respond(exchange, 2);
        });
        proxy.start();
        try {
            var profile = CrawlerTestHeaders.profile(
                    "Mozilla/5.0 Chrome/126",
                    CrawlerBrowserTemplate.NONE,
                    false,
                    Set.of(),
                    false,
                    CrawlerCaptchaBypassProvider.NONE,
                    Map.of("Accept", "application/json", "X-Forwarded-For", "198.51.100.1"));
            var transport = transportThrough(proxy, CrawlerTestHeaders.policy(profile));
            var response = transport.get(new CrawlerTransport.Request(
                    "source_test_crawler",
                    URI.create("http://target.test/headers"),
                    OutboundRoute.WEB_CRAWL,
                    Map.of("User-Agent", "ignored-by-profile"),
                    Duration.ofSeconds(5),
                    1_024,
                    1,
                    true,
                    "TEST_CRAWLER",
                    "Test crawler"));

            assertEquals(200, response.statusCode());
            assertEquals("Mozilla/5.0 Chrome/126", observedUserAgent.get());
            assertEquals("application/json", observedAccept.get());
            assertEquals("zh-CN,en;q=0.8", observedLanguage.get());
            assertEquals("198.51.100.1", observedForwarded.get());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void allowsForwardedIdentityHeadersForCamouflageProfiles() throws IOException {
        var observed = new AtomicReference<String>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/headers", exchange -> {
            observed.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            respond(exchange, 2);
        });
        proxy.start();
        try {
            var response = transportThrough(proxy).get(new CrawlerTransport.Request(
                    "source_test_crawler",
                    URI.create("http://target.test/headers"),
                    OutboundRoute.WEB_CRAWL,
                    Map.of("X-Forwarded-For", "198.51.100.1"),
                    Duration.ofSeconds(5),
                    1_024,
                    1,
                    true,
                    "TEST_CRAWLER",
                    "Test crawler"));
            assertEquals(200, response.statusCode());
            assertEquals("198.51.100.1", observed.get());
        } finally {
            proxy.stop(0);
        }
    }

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
                CrawlerTestHeaders.policy(),
                new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                CrawlerTestHeaders.noBypass());

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
                    CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass());

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
    void classifiesPlainForbiddenAsAccessBlockedWithChallengeKind() throws IOException {
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

            assertEquals("TEST_CRAWLER_ACCESS_BLOCKED", exception.errorCode());
            assertEquals(403, exception.statusCode());
            assertTrue(exception.blocked());
            assertEquals(Optional.of("UNKNOWN_BLOCK"), exception.challengeKind());
            assertEquals("challenge/unknown_block", exception.observationLabel());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void classifiesHttp200AnubisBotWallAsChallenge() throws IOException {
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/search", exchange -> {
            var body = """
                    <!doctype html><html><head><title>Making sure you're not a bot!</title>
                    <link href="/.within.website/x/xess/xess.min.css" rel="stylesheet"></head>
                    <body>challenge</body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var exception = assertThrows(SourceCollectionException.class,
                    () -> transportThrough(proxy).get(request(URI.create("http://target.test/search?format=json"), 1)));
            assertEquals("TEST_CRAWLER_CHALLENGE_ANUBIS", exception.errorCode());
            assertEquals(Optional.of("ANUBIS"), exception.challengeKind());
            assertEquals(200, exception.statusCode());
            assertTrue(exception.blocked());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void classifiesCloudflareTurnstileWithoutAttemptingBypassWhenDisabled() throws IOException {
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/gate", exchange -> {
            var body = """
                    <html><body>Just a moment...
                    <div class="cf-turnstile" data-sitekey="1x00000000000000000000AA"></div>
                    </body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.getResponseHeaders().set("cf-ray", "test");
                exchange.getResponseHeaders().set("server", "cloudflare");
                exchange.sendResponseHeaders(403, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var exception = assertThrows(SourceCollectionException.class,
                    () -> transportThrough(proxy).get(request(URI.create("http://target.test/gate"), 1)));

            assertEquals("TEST_CRAWLER_CHALLENGE_CLOUDFLARE_TURNSTILE", exception.errorCode());
            assertEquals(Optional.of("CLOUDFLARE_TURNSTILE"), exception.challengeKind());
            assertTrue(exception.getMessage().contains("Turnstile"));
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
    void removesCredentialsWhenRedirectingToAnotherOriginByDefault() throws IOException {
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

    @Test
    void retainsCredentialsOnCrossOriginRedirectWhenProfileAllows() throws IOException {
        var finalAuthorization = new AtomicReference<String>();
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
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var profile = CrawlerTestHeaders.profile(
                    "Mozilla/5.0",
                    CrawlerBrowserTemplate.NONE,
                    true,
                    Set.of(),
                    false,
                    CrawlerCaptchaBypassProvider.NONE,
                    Map.of());
            transportThrough(proxy, CrawlerTestHeaders.policy(profile)).get(new CrawlerTransport.Request(
                    "source_test_crawler",
                    URI.create("http://target.test/start"),
                    OutboundRoute.WEB_CRAWL,
                    Map.of("Authorization", "Bearer keep-me"),
                    Duration.ofSeconds(5),
                    1_024,
                    1,
                    true,
                    "TEST_CRAWLER",
                    "Test crawler"));
            assertEquals("Bearer keep-me", finalAuthorization.get());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void solvesCaptchaChallengeAndRetriesWhenBypassEnabled() throws IOException {
        var attempts = new AtomicInteger();
        var finalCookie = new AtomicReference<String>();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/gate", exchange -> {
            var count = attempts.incrementAndGet();
            if (count == 1) {
                var body = """
                        <html><body>Just a moment... cf-browser-verification
                        <div class="cf-turnstile" data-sitekey="1x00000000000000000000AA"></div>
                        </body></html>
                        """.getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.getResponseHeaders().set("cf-ray", "test");
                    exchange.getResponseHeaders().set("server", "cloudflare");
                    exchange.sendResponseHeaders(403, body.length);
                    exchange.getResponseBody().write(body);
                }
                return;
            }
            finalCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            var body = "ok".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        proxy.start();
        try {
            var profile = CrawlerTestHeaders.profile(
                    "Mozilla/5.0",
                    CrawlerBrowserTemplate.CHROME_WINDOWS,
                    false,
                    Set.of(),
                    true,
                    CrawlerCaptchaBypassProvider.CAPSOLVER,
                    Map.of());
            var policy = CrawlerTestHeaders.policy(profile);
            var proxyUri = URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
            ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                    route, true, false, proxyUri, "IPV4", proxyUri.toString());
            var transport = new CrawlerTransport(
                    new RoutedHttpClientFactory(resolver, Runnable::run),
                    new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(1)),
                    new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                    Clock.systemUTC(),
                    policy,
                    new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                    (challenge, provider) -> Optional.of(new CrawlerAccessChallengeBypass(
                            Map.of("cf-turnstile-response", "token"),
                            Map.of("cf_clearance", "cleared"),
                            "test-solver")));

            var response = transport.get(new CrawlerTransport.Request(
                    "source_test_crawler",
                    URI.create("http://target.test/gate"),
                    OutboundRoute.WEB_CRAWL,
                    Map.of(),
                    Duration.ofSeconds(5),
                    8_192,
                    1,
                    true,
                    "TEST_CRAWLER",
                    "Test crawler"));

            assertEquals(200, response.statusCode());
            assertEquals(2, attempts.get());
            assertTrue(finalCookie.get().contains("cf_clearance=cleared"));
        } finally {
            proxy.stop(0);
        }
    }

    private static CrawlerTransport transportThrough(HttpServer proxy) {
        return transportThrough(proxy, CrawlerTestHeaders.policy());
    }

    private static CrawlerTransport transportThrough(HttpServer proxy, CrawlerRequestHeaderPolicy policy) {
        var proxyUri = proxy == null
                ? URI.create("http://127.0.0.1:1")
                : URI.create("http://127.0.0.1:" + proxy.getAddress().getPort());
        ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                route, true, false, proxyUri, "IPV4", proxyUri.toString());
        return new CrawlerTransport(
                new RoutedHttpClientFactory(resolver, Runnable::run),
                new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(1)),
                new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                policy,
                new io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector(),
                CrawlerTestHeaders.noBypass());
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
