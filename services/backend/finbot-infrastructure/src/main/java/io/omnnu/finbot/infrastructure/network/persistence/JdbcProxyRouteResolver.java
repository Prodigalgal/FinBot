package io.omnnu.finbot.infrastructure.network.persistence;

import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
import io.omnnu.finbot.application.network.exception.ProxyRouteUnavailableException;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcProxyRouteResolver implements ProxyRouteResolver {
    private final JdbcClient jdbcClient;
    private final RuntimeSecretStore runtimeSecrets;

    public JdbcProxyRouteResolver(JdbcClient jdbcClient, RuntimeSecretStore runtimeSecrets) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
    }

    @Override
    public ProxyRouteDecision resolve(OutboundRoute route) {
        var stored = jdbcClient.sql("""
                select require_proxy, allow_direct, proxy_url_env, expected_ip_family, enabled
                from network_proxy_route where route_type = :routeType
                """)
                .param("routeType", route.name())
                .query((resultSet, rowNumber) -> new StoredRoute(
                        resultSet.getBoolean("require_proxy"),
                        resultSet.getBoolean("allow_direct"),
                        resultSet.getString("proxy_url_env"),
                        resultSet.getString("expected_ip_family"),
                        resultSet.getBoolean("enabled")))
                .optional()
                .orElseThrow(() -> new ProxyRouteUnavailableException(
                        "Outbound route is not configured: " + route));
        if (!stored.enabled()) {
            throw new ProxyRouteUnavailableException("Outbound route is disabled: " + route);
        }
        var configuredValue = runtimeSecrets.resolve(
                        RuntimeSecretScope.PROXY_ROUTE,
                        route.name(),
                        "PROXY_URL",
                        stored.proxyUrlEnvironmentVariable())
                .orElse(null);
        if (configuredValue == null || configuredValue.isBlank()) {
            if (stored.requireProxy()) {
                throw new ProxyRouteUnavailableException(
                        "Required proxy route is not configured: " + route);
            }
            if (!stored.allowDirect()) {
                throw new ProxyRouteUnavailableException(
                        "Outbound route has neither a proxy nor direct access: " + route);
            }
            return new ProxyRouteDecision(
                    route,
                    false,
                    true,
                    null,
                    stored.expectedIpFamily(),
                    "direct");
        }
        var proxyUrl = parseProxyUrl(configuredValue);
        return new ProxyRouteDecision(
                route,
                stored.requireProxy(),
                stored.allowDirect(),
                proxyUrl,
                stored.expectedIpFamily(),
                proxyUrl.getScheme() + "://" + proxyUrl.getHost() + ':' + effectivePort(proxyUrl));
    }

    private static URI parseProxyUrl(String value) {
        var uri = URI.create(value.strip());
        if (uri.getHost() == null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new ProxyRouteUnavailableException(
                    "Proxy route must use an HTTP proxy URL exposed by the K8S egress gateway");
        }
        return uri;
    }

    public static int effectivePort(URI proxyUrl) {
        if (proxyUrl.getPort() > 0) {
            return proxyUrl.getPort();
        }
        return "https".equalsIgnoreCase(proxyUrl.getScheme()) ? 443 : 80;
    }

    private record StoredRoute(
            boolean requireProxy,
            boolean allowDirect,
            String proxyUrlEnvironmentVariable,
            String expectedIpFamily,
            boolean enabled) {
    }
}
