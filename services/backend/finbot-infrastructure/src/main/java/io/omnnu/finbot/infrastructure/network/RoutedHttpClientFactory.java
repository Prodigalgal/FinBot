package io.omnnu.finbot.infrastructure.network;

import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class RoutedHttpClientFactory {
    private final ProxyRouteResolver routeResolver;
    private final Executor executor;
    private final ConcurrentHashMap<ClientKey, HttpClient> clients = new ConcurrentHashMap<>();

    public RoutedHttpClientFactory(
            ProxyRouteResolver routeResolver,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public HttpClient client(OutboundRoute route) {
        Objects.requireNonNull(route, "route");
        var decision = routeResolver.resolve(route);
        var key = new ClientKey(route, decisionFingerprint(decision));
        clients.keySet().removeIf(existing -> existing.route() == route && !existing.equals(key));
        return clients.computeIfAbsent(key, ignored -> build(decision));
    }

    public HttpClient clientForRequest(ProxyRouteDecision decision) {
        return build(Objects.requireNonNull(decision, "decision"));
    }

    public ProxyRouteDecision route(OutboundRoute route) {
        return routeResolver.resolve(route);
    }

    private HttpClient build(ProxyRouteDecision decision) {
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2);
        if (!decision.proxied()) {
            return builder.build();
        }
        var proxyUrl = decision.proxyUrl();
        builder.proxy(ProxySelector.of(new InetSocketAddress(
                proxyUrl.getHost(),
                JdbcProxyRouteResolver.effectivePort(proxyUrl))));
        if (proxyUrl.getUserInfo() != null && !proxyUrl.getUserInfo().isBlank()) {
            var credentials = decodedCredentials(proxyUrl.getUserInfo());
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        return null;
                    }
                    return new PasswordAuthentication(
                            credentials.username(),
                            credentials.password().toCharArray());
                }
            });
        }
        return builder.build();
    }

    private static String decisionFingerprint(ProxyRouteDecision decision) {
        var value = decision.proxyUrl() == null ? "direct" : decision.proxyUrl().toASCIIString();
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ProxyCredentials decodedCredentials(String userInfo) {
        var separator = userInfo.indexOf(':');
        var username = separator < 0 ? userInfo : userInfo.substring(0, separator);
        var password = separator < 0 ? "" : userInfo.substring(separator + 1);
        return new ProxyCredentials(
                URLDecoder.decode(username, StandardCharsets.UTF_8),
                URLDecoder.decode(password, StandardCharsets.UTF_8));
    }

    private record ProxyCredentials(String username, String password) {
    }

    private record ClientKey(OutboundRoute route, String decisionFingerprint) {
    }
}
