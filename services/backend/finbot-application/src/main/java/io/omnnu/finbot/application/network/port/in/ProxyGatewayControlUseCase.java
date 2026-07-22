package io.omnnu.finbot.application.network.port.in;

import io.omnnu.finbot.application.network.dto.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.dto.ProxyGatewayReloadResult;
import io.omnnu.finbot.application.network.dto.ProxyGatewayRuntimeStatus;
import io.omnnu.finbot.application.network.dto.UpdateProxyGatewayProfileCommand;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ProxyGatewayControlUseCase {
    CompletionStage<ProxyGatewayReloadResult> reload(String gatewayId);

    CompletionStage<ProxyGatewayRuntimeStatus> status(String gatewayId);

    ProxyGatewayProfile update(UpdateProxyGatewayProfileCommand command);

    List<CompletionStage<Void>> reconcileAll();
}
