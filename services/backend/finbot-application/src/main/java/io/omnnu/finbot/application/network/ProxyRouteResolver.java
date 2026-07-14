package io.omnnu.finbot.application.network;

import io.omnnu.finbot.domain.network.OutboundRoute;

@FunctionalInterface
public interface ProxyRouteResolver {
    ProxyRouteDecision resolve(OutboundRoute route);
}
