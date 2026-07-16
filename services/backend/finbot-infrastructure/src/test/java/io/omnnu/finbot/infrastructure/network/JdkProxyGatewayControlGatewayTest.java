package io.omnnu.finbot.infrastructure.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.network.ProxyGatewayApplyMode;
import io.omnnu.finbot.application.network.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.ProxyGatewayRuntimeConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkProxyGatewayControlGatewayTest {
    @Test
    void readsAuthenticatedTargetAwareRuntimeStatus() throws Exception {
        var authorization = new AtomicReference<String>();
        var reloadQueries = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/control/status", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var body = """
                    {
                      "serviceReady": true,
                      "ready": false,
                      "nodeCount": 4,
                      "healthyNodeCount": 0,
                      "unhealthyNodeCount": 4,
                      "healthyNodeIndices": [],
                      "probeFailureCounts": {"CONNECTION_ERROR": 4},
                      "validationEnabled": true,
                      "validationTarget": "api-demo.bybit.com",
                      "generation": 3,
                      "refreshAttempt": 5,
                      "lastRefreshEpochSeconds": 1784220690.125,
                      "lastError": "Proxy target validation found no healthy nodes"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/control/config", exchange -> {
            reloadQueries.add(Optional.ofNullable(exchange.getRequestURI().getRawQuery())
                    .orElse("<none>"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            EnvironmentValueResolver environment = name -> Optional.of("control-token");
            var gateway = new JdkProxyGatewayControlGateway(
                    HttpClient.newHttpClient(), new ObjectMapper(), environment);
            var profile = new ProxyGatewayProfile(
                    "proxygateway_exchange",
                    "交易所代理池",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    null,
                    "EXCHANGE_PROXY_NODES",
                    List.of("JP"),
                    16,
                    1800,
                    false,
                    true,
                    1,
                    Instant.parse("2026-07-17T00:00:00Z"));

            var status = gateway.status(profile).toCompletableFuture().join();
            var configuration = new ProxyGatewayRuntimeConfiguration(
                    "https://subscription.example/proxies",
                    null,
                    List.of("JP"),
                    16,
                    1800,
                    false);
            gateway.apply(profile, configuration, ProxyGatewayApplyMode.FORCE_RELOAD)
                    .toCompletableFuture()
                    .join();
            gateway.apply(profile, configuration, ProxyGatewayApplyMode.RECONCILE)
                    .toCompletableFuture()
                    .join();

            assertEquals("Bearer control-token", authorization.get());
            assertEquals(List.of("force=true", "<none>"), reloadQueries);
            assertThrows(NullPointerException.class, () -> gateway.apply(profile, configuration, null));
            assertTrue(status.serviceReady());
            assertFalse(status.egressReady());
            assertEquals(4, status.nodeCount());
            assertEquals(0, status.healthyNodeCount());
            assertEquals(Map.of("CONNECTION_ERROR", 4), status.probeFailureCounts());
            assertEquals("api-demo.bybit.com", status.validationTarget());
            assertEquals(Instant.ofEpochMilli(1784220690125L), status.lastRefreshAt());
        } finally {
            server.stop(0);
        }
    }
}
