package io.omnnu.finbot.application.network;

import java.time.Instant;
import java.util.List;

public interface NetworkDiagnosticStore {
    NetworkDiagnosticBatchClaim prepareBatch(
            String batchId,
            String batchIdempotencyKey,
            String requestFingerprint,
            Instant createdAt);

    NetworkDiagnosticStart start(
            String diagnosticId,
            String batchIdempotencyKey,
            io.omnnu.finbot.domain.network.OutboundRoute route,
            Instant startedAt);

    NetworkDiagnostic complete(
            String diagnosticId,
            ProxyRouteDecision decision,
            NetworkProbeResult result,
            Instant completedAt);

    NetworkDiagnostic block(
            String diagnosticId,
            io.omnnu.finbot.domain.network.OutboundRoute route,
            String safeMessage,
            Instant completedAt);

    List<NetworkDiagnostic> list(int limit);
}
