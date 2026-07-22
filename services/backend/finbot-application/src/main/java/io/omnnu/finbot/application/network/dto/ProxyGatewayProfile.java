package io.omnnu.finbot.application.network.dto;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ProxyGatewayProfile(
        String gatewayId,
        String displayName,
        URI controlUrl,
        String subscriptionUrlEnvironment,
        String inlineNodesEnvironment,
        ProxyEngine engine,
        List<String> preferredNames,
        int maximumNodes,
        int refreshSeconds,
        boolean allowInsecureTls,
        boolean enabled,
        long version,
        Instant updatedAt) {
    public ProxyGatewayProfile {
        gatewayId = Objects.requireNonNull(gatewayId, "gatewayId").strip();
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        Objects.requireNonNull(controlUrl, "controlUrl");
        Objects.requireNonNull(engine, "engine");
        preferredNames = List.copyOf(preferredNames);
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
