package io.omnnu.finbot.application.network.dto;

import java.util.List;

public record ProxyGatewayRuntimeConfiguration(
        String subscriptionUrl,
        String inlineNodes,
        ProxyEngine engine,
        List<String> preferredNames,
        int maximumNodes,
        int refreshSeconds,
        boolean allowInsecureTls,
        boolean enabled) {
    public ProxyGatewayRuntimeConfiguration {
        if (engine == null) {
            throw new IllegalArgumentException("Proxy gateway engine is required");
        }
        preferredNames = List.copyOf(preferredNames);
        if (enabled
                && (subscriptionUrl == null || subscriptionUrl.isBlank())
                && (inlineNodes == null || inlineNodes.isBlank())) {
            throw new IllegalArgumentException("Proxy gateway requires a subscription URL or inline nodes");
        }
    }
}
