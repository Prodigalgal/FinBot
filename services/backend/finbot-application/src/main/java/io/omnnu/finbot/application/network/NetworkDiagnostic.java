package io.omnnu.finbot.application.network;

import io.omnnu.finbot.domain.network.OutboundRoute;
import java.time.Instant;

public record NetworkDiagnostic(
        String diagnosticId,
        OutboundRoute route,
        String status,
        boolean proxyConfigured,
        boolean proxied,
        String safeEndpoint,
        Integer httpStatus,
        Long latencyMilliseconds,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
