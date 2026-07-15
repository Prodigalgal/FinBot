package io.omnnu.finbot.application.network;

import io.omnnu.finbot.domain.network.OutboundRoute;
import java.util.List;

public interface NetworkDiagnosticsUseCase {
    List<NetworkDiagnostic> start(List<OutboundRoute> routes, String idempotencyKey);

    List<NetworkDiagnostic> history(int limit);
}
