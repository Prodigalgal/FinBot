package io.omnnu.finbot.infrastructure.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExchangeAccountHttpTransportTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientHttpFailureAndBuildsAFreshSignedRequest() throws IOException {
        var calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/account", exchange -> {
            var call = calls.incrementAndGet();
            respond(exchange, call == 1 ? 503 : 200, "{\"ok\":true}");
        });
        server.start();

        var requests = new AtomicInteger();
        var transport = transport(3, Duration.ZERO);
        var response = transport.send(
                OutboundRoute.EXCHANGE_BYBIT,
                () -> request(serverUri("/account"), "signature-" + requests.incrementAndGet()));

        assertEquals(200, response.statusCode());
        assertEquals(2, response.attempts());
        assertEquals(2, requests.get());
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryAuthenticationFailures() throws IOException {
        var calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/account", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 401, "{\"error\":\"invalid key\"}");
        });
        server.start();

        var transport = transport(3, Duration.ZERO);
        var response = transport.send(
                OutboundRoute.EXCHANGE_BYBIT,
                () -> request(serverUri("/account"), "signature"));

        assertEquals(401, response.statusCode());
        assertEquals(1, response.attempts());
        assertEquals(1, calls.get());
    }

    @Test
    void classifiesRepeatedConnectionEofAsNetworkFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/account", HttpExchange::close);
        server.start();

        var transport = transport(2, Duration.ZERO);
        var failure = assertThrows(
                ExchangeAccountTransportException.class,
                () -> transport.send(
                        OutboundRoute.EXCHANGE_BYBIT,
                        () -> request(serverUri("/account"), "signature")));

        assertEquals("EXCHANGE_ACCOUNT_NETWORK_FAILURE", failure.errorCode());
        assertEquals(2, failure.attempts());
    }

    private ExchangeAccountHttpTransport transport(int attempts, Duration backoff) {
        var resolver = new DirectResolver();
        return new ExchangeAccountHttpTransport(
                new RoutedHttpClientFactory(resolver, Runnable::run),
                new ObjectMapper(),
                attempts,
                backoff.isZero() ? Duration.ofNanos(1) : backoff);
    }

    private HttpRequest request(URI uri, String signature) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(2))
                .header("X-Test-Signature", signature)
                .GET()
                .build();
    }

    private URI serverUri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static final class DirectResolver implements ProxyRouteResolver {
        @Override
        public ProxyRouteDecision resolve(OutboundRoute route) {
            return new ProxyRouteDecision(route, false, true, null, "ANY", "DIRECT");
        }
    }
}
