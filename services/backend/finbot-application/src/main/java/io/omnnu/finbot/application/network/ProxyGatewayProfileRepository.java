package io.omnnu.finbot.application.network;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProxyGatewayProfileRepository {
    List<ProxyGatewayProfile> list();

    Optional<ProxyGatewayProfile> find(String gatewayId);

    Optional<ProxyGatewayProfile> update(
            ProxyGatewayProfile profile,
            long expectedVersion,
            Instant updatedAt);
}
