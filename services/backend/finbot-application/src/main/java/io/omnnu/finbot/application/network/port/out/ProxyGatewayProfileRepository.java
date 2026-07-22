package io.omnnu.finbot.application.network.port.out;

import io.omnnu.finbot.application.network.dto.ProxyGatewayProfile;

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
