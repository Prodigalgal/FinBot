package io.omnnu.finbot.application.network;

import java.util.concurrent.CompletionStage;

public interface ProxyGatewayControlGateway {
    CompletionStage<Void> apply(
            ProxyGatewayProfile profile,
            ProxyGatewayRuntimeConfiguration configuration);
}
