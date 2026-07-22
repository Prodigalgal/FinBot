package io.omnnu.finbot.application.network.dto;

import java.time.Instant;

public record ProxyGatewayReloadResult(
        String gatewayId,
        String status,
        Instant requestedAt) {
}
