package io.omnnu.finbot.application.network;

@FunctionalInterface
public interface NetworkProbeGateway {
    NetworkProbeResult probe(ProxyRouteDecision route);
}
