package io.omnnu.finbot.api.network.controller;

import io.omnnu.finbot.application.network.port.in.ProxyGatewayControlUseCase;
import io.omnnu.finbot.application.network.dto.ProxyEngine;
import io.omnnu.finbot.application.network.dto.ProxyGatewayReloadResult;
import io.omnnu.finbot.application.network.dto.ProxyGatewayRuntimeStatus;
import io.omnnu.finbot.application.network.dto.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.dto.UpdateProxyGatewayProfileCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/network/proxy-gateways")
public final class ProxyGatewayControlController {
    private final ProxyGatewayControlUseCase useCase;

    public ProxyGatewayControlController(ProxyGatewayControlUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @PostMapping("/{gatewayId}/reload")
    public CompletionStage<ProxyGatewayReloadResult> reload(@PathVariable String gatewayId) {
        return useCase.reload(gatewayId);
    }

    @GetMapping("/{gatewayId}")
    public CompletionStage<ProxyGatewayRuntimeStatus> status(@PathVariable String gatewayId) {
        return useCase.status(gatewayId);
    }

    @PutMapping("/{gatewayId}")
    public ProxyGatewayProfile update(
            @PathVariable String gatewayId,
            @Valid @RequestBody UpdateRequest request) {
        return useCase.update(new UpdateProxyGatewayProfileCommand(
                gatewayId,
                request.engine(),
                request.preferredNames(),
                request.maximumNodes(),
                request.refreshSeconds(),
                request.allowInsecureTls(),
                request.enabled(),
                request.expectedVersion()));
    }

    public record UpdateRequest(
            ProxyEngine engine,
            @Size(max = 32) List<@Size(min = 1, max = 80) String> preferredNames,
            @Min(1) @Max(128) int maximumNodes,
            @Min(60) @Max(86_400) int refreshSeconds,
            boolean allowInsecureTls,
            boolean enabled,
            @Min(0) long expectedVersion) {
        public UpdateRequest {
            Objects.requireNonNull(engine, "engine");
            preferredNames = preferredNames == null ? List.of() : List.copyOf(preferredNames);
        }
    }
}
