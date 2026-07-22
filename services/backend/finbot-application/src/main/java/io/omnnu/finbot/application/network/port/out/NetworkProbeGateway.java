package io.omnnu.finbot.application.network.port.out;

import io.omnnu.finbot.application.network.dto.NetworkProbeResult;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;

@FunctionalInterface
public interface NetworkProbeGateway {
    NetworkProbeResult probe(ProxyRouteDecision route);
}
