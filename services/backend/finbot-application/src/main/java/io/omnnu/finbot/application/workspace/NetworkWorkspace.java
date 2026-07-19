package io.omnnu.finbot.application.workspace;

import io.omnnu.finbot.application.network.ProxyEngine;
import java.time.Instant;
import java.util.List;

public record NetworkWorkspace(
        List<Route> routes,
        List<ProxyGateway> proxyGateways,
        Instant generatedAt) {
    public NetworkWorkspace {
        routes = List.copyOf(routes);
        proxyGateways = List.copyOf(proxyGateways);
    }

    public record Route(
            String routeId,
            String routeType,
            String displayName,
            boolean enabled,
            boolean requireProxy,
            boolean allowDirect,
            boolean proxyConfigured,
            String proxyCredentialSource,
            String proxyCredentialFingerprint,
            long proxyCredentialVersion,
            String expectedIpFamily,
            String resolvedEndpoint,
            String status,
            String latestDependencyStatus,
            String latestError,
            Instant latestActivityAt,
            Instant updatedAt) {
    }

    public record ProxyGateway(
            String gatewayId,
            String displayName,
            boolean enabled,
            ProxyEngine engine,
            String preferredNames,
            int maximumNodes,
            int refreshSeconds,
            boolean allowInsecureTls,
            boolean subscriptionSupported,
            String subscriptionSource,
            String subscriptionFingerprint,
            long subscriptionVersion,
            boolean inlineNodesSupported,
            String inlineNodesSource,
            String inlineNodesFingerprint,
            long inlineNodesVersion,
            String status,
            long version,
            Instant updatedAt) {
    }
}
