package io.omnnu.finbot.application.network;

import java.util.List;

public record ProxyGatewayRuntimeConfiguration(
        String subscriptionUrl,
        String inlineNodes,
        List<String> preferredNames,
        int maximumNodes,
        int refreshSeconds,
        boolean allowInsecureTls) {
    public ProxyGatewayRuntimeConfiguration {
        preferredNames = List.copyOf(preferredNames);
        if ((subscriptionUrl == null || subscriptionUrl.isBlank())
                && (inlineNodes == null || inlineNodes.isBlank())) {
            throw new IllegalArgumentException("Proxy gateway requires a subscription URL or inline nodes");
        }
    }
}
