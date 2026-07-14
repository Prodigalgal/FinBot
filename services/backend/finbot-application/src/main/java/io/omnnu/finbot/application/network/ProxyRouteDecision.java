package io.omnnu.finbot.application.network;

import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import java.util.Objects;

public record ProxyRouteDecision(
        OutboundRoute route,
        boolean proxyRequired,
        boolean directAllowed,
        URI proxyUrl,
        String expectedIpFamily,
        String redactedEndpoint) {
    public ProxyRouteDecision {
        Objects.requireNonNull(route, "route");
        expectedIpFamily = Objects.requireNonNull(expectedIpFamily, "expectedIpFamily").strip();
        redactedEndpoint = Objects.requireNonNull(redactedEndpoint, "redactedEndpoint").strip();
        if (proxyRequired && (directAllowed || proxyUrl == null)) {
            throw new IllegalArgumentException("Required proxy route must resolve to a proxy without direct fallback");
        }
        if (proxyUrl != null && proxyUrl.getHost() == null) {
            throw new IllegalArgumentException("Proxy URL is invalid");
        }
    }

    public boolean proxied() {
        return proxyUrl != null;
    }
}
