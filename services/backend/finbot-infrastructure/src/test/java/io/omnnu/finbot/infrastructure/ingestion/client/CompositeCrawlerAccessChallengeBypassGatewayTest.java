package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.CompositeCrawlerAccessChallengeBypassGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.application.ingestion.dto.CrawlerAccessChallenge;
import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.infrastructure.network.client.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CompositeCrawlerAccessChallengeBypassGatewayTest {
    @Test
    void callsBrowserWorkerAndReturnsValidatedReplayMaterial() throws IOException {
        var authorization = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"final_url":"https://example.com/search","status_code":200,
                     "cookies":{"cf_clearance":"ok"},"user_agent":"browser-ua",
                     "title":"Search","challenge_hints":[],"detail":"playwright-chromium;ok"}
                    """);
        });
        try {
            var result = gateway(server).solve(challenge(), CrawlerCaptchaBypassProvider.BROWSER_WORKER).orElseThrow();

            assertEquals("Bearer internal-token", authorization.get());
            assertTrue(requestBody.get().contains("\"wait_until\":\"networkidle\""));
            assertEquals(Map.of("cf_clearance", "ok"), result.cookies());
            assertEquals(Map.of("User-Agent", "browser-ua"), result.extraHeaders());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCrossOriginBrowserResult() throws IOException {
        var server = server(exchange -> respond(exchange, 200, """
                {"final_url":"https://attacker.example/","status_code":200,
                 "cookies":{"session":"ok"},"user_agent":"browser-ua",
                 "detail":"playwright-chromium;ok"}
                """));
        try {
            var error = assertThrows(SourceCollectionException.class, () ->
                    gateway(server).solve(challenge(), CrawlerCaptchaBypassProvider.BROWSER_WORKER));

            assertEquals("CRAWLER_CAPTCHA_BYPASS_FAILED", error.errorCode());
            assertTrue(error.getMessage().contains("crossed the requested origin"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesRetryableCapacityFailureAsSafeBypassError() throws IOException {
        var server = server(exchange -> respond(exchange, 429, "{\"detail\":\"capacity exhausted\"}"));
        try {
            var error = assertThrows(SourceCollectionException.class, () ->
                    gateway(server).solve(challenge(), CrawlerCaptchaBypassProvider.BROWSER_WORKER));

            assertEquals("CRAWLER_CAPTCHA_BYPASS_FAILED", error.errorCode());
            assertTrue(error.getMessage().contains("HTTP 429"));
            assertTrue(error.blocked());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsInvalidCookieReplayMaterialToSafeBypassError() throws IOException {
        var server = server(exchange -> respond(exchange, 200, """
                {"final_url":"https://example.com/search","status_code":200,
                 "cookies":{"session":"valid\\r\\nX-Injected: true"},"user_agent":"browser-ua",
                 "detail":"playwright-chromium;ok"}
                """));
        try {
            var error = assertThrows(SourceCollectionException.class, () ->
                    gateway(server).solve(challenge(), CrawlerCaptchaBypassProvider.BROWSER_WORKER));

            assertEquals("CRAWLER_CAPTCHA_BYPASS_FAILED", error.errorCode());
            assertTrue(error.getMessage().contains("invalid replay material"));
            assertTrue(error.blocked());
        } finally {
            server.stop(0);
        }
    }

    private static CompositeCrawlerAccessChallengeBypassGateway gateway(HttpServer server) {
        var values = Map.of(
                "FINBOT_BROWSER_WORKER_URL", "http://127.0.0.1:" + server.getAddress().getPort(),
                "FINBOT_BROWSER_WORKER_TOKEN", "internal-token");
        var directClients = new RoutedHttpClientFactory(
                route -> new ProxyRouteDecision(route, false, true, null, "ANY", "direct"),
                Runnable::run);
        return new CompositeCrawlerAccessChallengeBypassGateway(
                new MapRuntimeSecretStore(values),
                directClients,
                new ObjectMapper());
    }

    private static CrawlerAccessChallenge challenge() {
        return new CrawlerAccessChallenge(
                CrawlerAccessChallenge.Kind.ANUBIS,
                URI.create("https://example.com/search"),
                null,
                "proof-of-work",
                Map.of(),
                200,
                CrawlerCaptchaBypassProvider.BROWSER_WORKER);
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/challenge/solve", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record MapRuntimeSecretStore(Map<String, String> values) implements RuntimeSecretStore {
        @Override
        public Optional<String> resolve(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return Optional.ofNullable(values.get(fallbackEnvironmentVariable));
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
