package io.omnnu.finbot.application.network.port.out;

import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;

import io.omnnu.finbot.domain.network.OutboundRoute;

@FunctionalInterface
public interface ProxyRouteResolver {
    ProxyRouteDecision resolve(OutboundRoute route);
}
