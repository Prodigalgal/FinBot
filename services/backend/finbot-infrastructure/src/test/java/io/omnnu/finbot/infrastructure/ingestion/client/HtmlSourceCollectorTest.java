package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerPolitenessController;
import io.omnnu.finbot.infrastructure.ingestion.client.HtmlSourceCollector;
import io.omnnu.finbot.infrastructure.ingestion.client.JsoupContentEnvelopeBuilder;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerConcurrencyLimiter;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HtmlSourceCollectorTest {
    @Test
    void fetchesHtmlThroughTheConfiguredRouteAndKeepsRawContentForAiCleaning() throws IOException {
        var requests = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/article", exchange -> respond(exchange, requests));
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
            var collector = new HtmlSourceCollector(
                    new CrawlerTransport(
                            new RoutedHttpClientFactory(resolver, Runnable::run),
                            limiter(),
                            new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                            Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                            CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.service.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()),
                    new JsoupContentEnvelopeBuilder());

            var payloads = collector.collect(source(), "energy update");

            assertEquals(1, requests.get());
            assertEquals(1, payloads.size());
            var payload = payloads.getFirst();
            assertEquals("Energy update", payload.title());
            assertEquals("https://example.com/articles/energy-update", payload.canonicalUrl().toString());
            assertTrue(payload.rawContent().contains("<script>"));
            assertEquals("first_party_html", payload.metadata().get("collector"));
            assertTrue(payload.envelope().normalizedText().contains("Navigation is kept"));
            assertTrue(payload.envelope().normalizedText().contains("Footer noise"));
            assertFalse(payload.envelope().normalizedText().contains("window.untrusted"));
            assertEquals("jsoup-blocks-v1", payload.envelope().metadata().get("builder"));
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void marksHttp403AsBlockedInsteadOfMisclassifyingTheBody() throws IOException {
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/forbidden", exchange -> respond(exchange, 403, "text/html", "blocked"));
        proxy.start();
        try {
            var collector = collectorFor(URI.create("http://127.0.0.1:" + proxy.getAddress().getPort()));
            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> collector.collect(source(URI.create("http://example.test/forbidden")), ""));

            assertEquals("HTML_ACCESS_BLOCKED", exception.errorCode());
            assertEquals(java.util.Optional.of("UNKNOWN_BLOCK"), exception.challengeKind());
            assertTrue(exception.blocked());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void rejectsDirectHtmlRequestsWhenTheRouteHasNoProxy() throws IOException {
        var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.start();
        try {
            var directResolver = (ProxyRouteResolver) route -> new ProxyRouteDecision(
                    route,
                    false,
                    true,
                    null,
                    "IPV4",
                    "direct");
            var collector = new HtmlSourceCollector(
                    new CrawlerTransport(
                            new RoutedHttpClientFactory(directResolver, Runnable::run),
                            limiter(),
                            new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                            Clock.systemUTC(),
                            CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.service.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()),
                    new JsoupContentEnvelopeBuilder());
            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> collector.collect(source(URI.create("http://example.test/article")), ""));

            assertEquals("HTML_PROXY_REQUIRED", exception.errorCode());
            assertTrue(exception.blocked());
        } finally {
            proxy.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, AtomicInteger requests) throws IOException {
        requests.incrementAndGet();
        var body = """
                <!doctype html>
                <html><head>
                  <title>Energy update</title>
                  <link rel="canonical" href="https://example.com/articles/energy-update">
                  <script>window.untrusted = true;</script>
                </head><body>
                  <nav>Navigation is kept for the AI cleaner to classify.</nav>
                  <main><h1>Energy update</h1><p>Inventory fell this week.</p></main>
                  <footer>Footer noise</footer>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String content)
            throws IOException {
        var body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static HtmlSourceCollector collectorFor(URI proxyUri) {
        ProxyRouteResolver resolver = route -> new ProxyRouteDecision(
                route,
                true,
                false,
                proxyUri,
                "IPV4",
                proxyUri.toString());
        return new HtmlSourceCollector(
                new CrawlerTransport(
                        new RoutedHttpClientFactory(resolver, Runnable::run),
                        limiter(),
                        new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                        Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                        CrawlerTestHeaders.policy(),
                    new io.omnnu.finbot.application.ingestion.service.CrawlerAccessChallengeDetector(),
                    CrawlerTestHeaders.noBypass()),
                new JsoupContentEnvelopeBuilder());
    }

    private static CrawlerConcurrencyLimiter limiter() {
        return new CrawlerConcurrencyLimiter(16, 2, 2, Duration.ofSeconds(1));
    }

    private static InformationSource source() {
        return source(URI.create("http://example.test/article"));
    }

    private static InformationSource source(URI seedUrl) {
        return new InformationSource(
                new SourceId("source_html_test01"),
                "HTML test",
                SourceMode.HTML_DOCUMENT,
                SourceTier.T1,
                "energy",
                "test",
                new BigDecimal("0.9"),
                900,
                SourcePriority.P1,
                List.of("USOIL"),
                List.of(),
                List.of(seedUrl),
                List.of(),
                null,
                null,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                true,
                0);
    }
}
