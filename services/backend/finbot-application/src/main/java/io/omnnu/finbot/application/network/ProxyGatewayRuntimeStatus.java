package io.omnnu.finbot.application.network;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProxyGatewayRuntimeStatus(
        String gatewayId,
        ProxyEngine engine,
        boolean serviceReady,
        boolean egressReady,
        int nodeCount,
        int healthyNodeCount,
        int unhealthyNodeCount,
        List<Integer> healthyNodeIndices,
        Map<String, Integer> probeFailureCounts,
        boolean validationEnabled,
        String validationTarget,
        long generation,
        long refreshAttempt,
        Instant lastRefreshAt,
        String error) {
    public ProxyGatewayRuntimeStatus {
        gatewayId = Objects.requireNonNull(gatewayId, "gatewayId");
        engine = Objects.requireNonNull(engine, "engine");
        healthyNodeIndices = List.copyOf(healthyNodeIndices);
        probeFailureCounts = Map.copyOf(probeFailureCounts);
        if (nodeCount < 0 || healthyNodeCount < 0 || unhealthyNodeCount < 0
                || healthyNodeCount + unhealthyNodeCount != nodeCount
                || healthyNodeIndices.size() != healthyNodeCount
                || healthyNodeIndices.stream().anyMatch(index -> index == null || index < 0)
                || probeFailureCounts.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null || entry.getKey().isBlank()
                                || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("Proxy gateway runtime health counts are invalid");
        }
    }
}
