package io.omnnu.finbot.application.network.port.out;

import io.omnnu.finbot.application.network.dto.ProxyGatewayApplyMode;
import io.omnnu.finbot.application.network.dto.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.dto.ProxyGatewayRuntimeConfiguration;
import io.omnnu.finbot.application.network.dto.ProxyGatewayRuntimeStatus;

import java.util.concurrent.CompletionStage;

public interface ProxyGatewayControlGateway {
    CompletionStage<Void> apply(
            ProxyGatewayProfile profile,
            ProxyGatewayRuntimeConfiguration configuration,
            ProxyGatewayApplyMode mode);

    CompletionStage<ProxyGatewayRuntimeStatus> status(ProxyGatewayProfile profile);
}
