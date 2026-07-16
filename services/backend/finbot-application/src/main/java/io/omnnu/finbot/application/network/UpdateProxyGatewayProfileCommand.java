package io.omnnu.finbot.application.network;

import java.util.List;

public record UpdateProxyGatewayProfileCommand(
        String gatewayId,
        List<String> preferredNames,
        int maximumNodes,
        int refreshSeconds,
        boolean allowInsecureTls,
        boolean enabled,
        long expectedVersion) {
    public UpdateProxyGatewayProfileCommand {
        preferredNames = List.copyOf(preferredNames);
    }
}
