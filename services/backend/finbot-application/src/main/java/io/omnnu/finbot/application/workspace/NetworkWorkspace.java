package io.omnnu.finbot.application.workspace;

import java.time.Instant;
import java.util.List;

public record NetworkWorkspace(
        List<Route> routes,
        Instant generatedAt) {
    public NetworkWorkspace {
        routes = List.copyOf(routes);
    }

    public record Route(
            String routeId,
            String routeType,
            String displayName,
            boolean enabled,
            boolean requireProxy,
            boolean allowDirect,
            boolean proxyConfigured,
            String expectedIpFamily,
            String resolvedEndpoint,
            String status,
            String latestDependencyStatus,
            String latestError,
            Instant latestActivityAt,
            Instant updatedAt) {
    }
}
