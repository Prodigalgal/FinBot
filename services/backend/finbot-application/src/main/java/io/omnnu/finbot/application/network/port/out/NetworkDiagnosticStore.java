package io.omnnu.finbot.application.network.port.out;

import io.omnnu.finbot.application.network.dto.NetworkDiagnostic;
import io.omnnu.finbot.application.network.dto.NetworkDiagnosticBatchClaim;
import io.omnnu.finbot.application.network.dto.NetworkDiagnosticStart;
import io.omnnu.finbot.application.network.dto.NetworkProbeResult;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;

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
