package io.omnnu.finbot.application.network;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ProxyGatewayControlUseCase {
    CompletionStage<ProxyGatewayReloadResult> reload(String gatewayId);

    ProxyGatewayProfile update(UpdateProxyGatewayProfileCommand command);

    List<CompletionStage<ProxyGatewayReloadResult>> reloadAll();
}
