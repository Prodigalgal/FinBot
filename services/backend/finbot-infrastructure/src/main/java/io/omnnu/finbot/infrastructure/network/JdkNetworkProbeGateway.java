package io.omnnu.finbot.infrastructure.network;

import io.omnnu.finbot.application.network.NetworkProbeGateway;
import io.omnnu.finbot.application.network.NetworkProbeResult;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class JdkNetworkProbeGateway implements NetworkProbeGateway {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Map<OutboundRoute, URI> TARGETS = Map.of(
            OutboundRoute.WEB_CRAWL,
            URI.create("https://www.cloudflare.com/cdn-cgi/trace"),
            OutboundRoute.FIRECRAWL,
            URI.create("https://www.cloudflare.com/cdn-cgi/trace"),
            OutboundRoute.EXCHANGE_GATE,
            URI.create("https://fx-api-testnet.gateio.ws/api/v4/futures/usdt/contracts/BTC_USDT"),
            OutboundRoute.EXCHANGE_BYBIT,
            URI.create("https://api-demo.bybit.com/v5/market/time"),
            OutboundRoute.PUBLIC_DATA,
            URI.create("https://www.cloudflare.com/cdn-cgi/trace"));

    private final Executor executor;

    public JdkNetworkProbeGateway(
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public NetworkProbeResult probe(ProxyRouteDecision route) {
        Objects.requireNonNull(route, "route");
        var builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
        if (route.proxied()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    route.proxyUrl().getHost(),
                    effectivePort(route.proxyUrl()))));
        }
        var request = HttpRequest.newBuilder(TARGETS.get(route.route()))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json,text/plain;q=0.9")
                .header("User-Agent", "FinBot-Network-Diagnostics/2")
                .GET()
                .build();
        var started = System.nanoTime();
        try {
            var response = builder.build().send(request, HttpResponse.BodyHandlers.discarding());
            var latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            var ready = response.statusCode() >= 200 && response.statusCode() < 400;
            return new NetworkProbeResult(
                    ready,
                    response.statusCode(),
                    latency,
                    ready ? null : "UPSTREAM_HTTP_STATUS",
                    ready ? null : "诊断目标返回 HTTP " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Network diagnostic was interrupted", exception);
        } catch (java.io.IOException exception) {
            var latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new NetworkProbeResult(
                    false,
                    null,
                    latency,
                    "NETWORK_PROBE_FAILED",
                    "网络探测失败：" + exception.getClass().getSimpleName());
        }
    }

    private static int effectivePort(URI proxyUrl) {
        if (proxyUrl.getPort() > 0) {
            return proxyUrl.getPort();
        }
        return "https".equalsIgnoreCase(proxyUrl.getScheme()) ? 443 : 80;
    }
}
