package io.omnnu.finbot.application.network;

import java.util.List;

public record UpdateProxyGatewayProfileCommand(
        String gatewayId,
        ProxyEngine engine,
        List<String> preferredNames,
        int maximumNodes,
        int refreshSeconds,
        boolean allowInsecureTls,
        boolean enabled,
        long expectedVersion) {
    public UpdateProxyGatewayProfileCommand {
        if (engine == null) {
            throw new IllegalArgumentException("Proxy gateway engine is required");
        }
        preferredNames = List.copyOf(preferredNames);
    }
}
