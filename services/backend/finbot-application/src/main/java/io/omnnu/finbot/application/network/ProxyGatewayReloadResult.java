package io.omnnu.finbot.application.network;

import java.time.Instant;

public record ProxyGatewayReloadResult(
        String gatewayId,
        String status,
        Instant requestedAt) {
}
